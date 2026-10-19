package com.example.mcp.server;

import com.example.mcp.audit.McpAuditWriter;
import com.example.mcp.client.ToolBackend;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Protocol contract of the hand-written MCP dispatcher: version negotiation, tool
 * listing, tool dispatch, and the error taxonomy (protocol errors vs tool errors).
 */
class McpDispatcherTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ToolBackend backend;
    private McpDispatcher dispatcher;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        backend = mock(ToolBackend.class);
        McpAuditWriter audit = new McpAuditWriter(
                tempDir.resolve("audit.jsonl").toString(), mapper);
        dispatcher = new McpDispatcher(backend, audit, mapper);
    }

    @Test
    void initializeEchoesSupportedVersionAndFallsBackToLatest() throws Exception {
        JsonNode echoed = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2024-11-05\"}}");
        assertEquals("2024-11-05", echoed.path("result").path("protocolVersion").asText());

        JsonNode fallback = call("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"1999-01-01\"}}");
        assertEquals(McpDispatcher.LATEST_PROTOCOL_VERSION,
                fallback.path("result").path("protocolVersion").asText());
        assertEquals(McpDispatcher.SERVER_NAME,
                fallback.path("result").path("serverInfo").path("name").asText());
    }

    @Test
    void toolsListExposesReadOnlyToolsAndCatalog() throws Exception {
        JsonNode tools = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
                .path("result").path("tools");
        assertEquals(5, tools.size());
        assertEquals("list_knowledge_spaces", tools.get(0).path("name").asText());
        assertEquals("search_video_knowledge", tools.get(1).path("name").asText());
        assertTrue(tools.get(1).path("inputSchema").path("required").toString().contains("query"));
        assertEquals("ask_video_knowledge", tools.get(2).path("name").asText());
        assertTrue(tools.get(2).path("inputSchema").path("required").toString().contains("query"));
        assertEquals("get_video_evidence", tools.get(3).path("name").asText());
        assertTrue(tools.get(3).path("inputSchema").path("required").toString().contains("mediaId"));
    }

    @Test
    void toolCallReturnsBackendTextAsContent() throws Exception {
        when(backend.searchKnowledge(eq("浏阳河"), isNull(), isNull(), isNull(), isNull()))
                .thenReturn("[{\"title\":\"洋来作品.mp4\"}]");
        JsonNode result = call("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"search_video_knowledge\","
                        + "\"arguments\":{\"query\":\"浏阳河\"}}}")
                .path("result");
        assertFalse(result.path("isError").asBoolean(false));
        assertEquals("text", result.path("content").get(0).path("type").asText());
        assertTrue(result.path("content").get(0).path("text").asText().contains("洋来作品"));
    }

    @Test
    void askToolForwardsArgumentsToBackend() throws Exception {
        when(backend.askKnowledge(eq("三次握手的过程是什么？"), eq(6L), eq(9L), eq(8), eq("hybrid")))
                .thenReturn("{\"answerability\":\"SUPPORTED\",\"answer\":\"三次握手是…\",\"citations\":[]}");
        JsonNode result = call("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"ask_video_knowledge\","
                        + "\"arguments\":{\"query\":\"三次握手的过程是什么？\","
                        + "\"spaceId\":6,\"collectionId\":9,\"topK\":8,\"strategy\":\"hybrid\"}}}")
                .path("result");
        assertFalse(result.path("isError").asBoolean(false));
        assertTrue(result.path("content").get(0).path("text").asText().contains("三次握手"));

        assertEquals(-32602, call("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"ask_video_knowledge\",\"arguments\":{}}}")
                .path("error").path("code").asInt());
    }

    @Test
    void unknownToolIsAProtocolErrorAndBackendFailureIsAToolError() throws Exception {
        assertEquals(-32602, call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"delete_everything\",\"arguments\":{}}}")
                .path("error").path("code").asInt());

        when(backend.listSpaces()).thenThrow(new RuntimeException("upstream down"));
        JsonNode result = call("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"list_knowledge_spaces\"}}")
                .path("result");
        assertTrue(result.path("isError").asBoolean());
        assertTrue(result.path("content").get(0).path("text").asText().contains("upstream down"));
    }

    @Test
    void notificationsProduceNoResponseAndBatchesAnswerOnlyRequests() throws Exception {
        assertNull(dispatcher.handle(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", "test").body());

        McpDispatcher.Outcome batch = dispatcher.handle(
                "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"},"
                        + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}]",
                "test");
        JsonNode responses = mapper.readTree(batch.body());
        assertTrue(responses.isArray());
        assertEquals(1, responses.size());
        assertNotNull(responses.get(0).path("result"));
    }

    @Test
    void unknownMethodAndMalformedJsonMapToStandardErrors() throws Exception {
        assertEquals(-32601, call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/list\"}")
                .path("error").path("code").asInt());
        assertEquals(-32700, dispatcher.handle("{not json", "test").body() != null
                ? mapper.readTree(dispatcher.handle("{not json", "test").body())
                        .path("error").path("code").asInt()
                : -1);
    }

    @Test
    void toolArgumentsAreValidatedBeforeReachingTheBackend() throws Exception {
        assertEquals(-32602, call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"search_video_knowledge\",\"arguments\":{}}}")
                .path("error").path("code").asInt());
        assertEquals(-32602, call("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"get_video_evidence\",\"arguments\":{}}}")
                .path("error").path("code").asInt());
        assertEquals(-32602, call("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"search_video_knowledge\","
                        + "\"arguments\":{\"query\":\"x\",\"topK\":99}}}")
                .path("error").path("code").asInt());
    }

    @Test
    void catalogDiscoveryCallsAuthorizedBackend() throws Exception {
        when(backend.knowledgeCatalog(7L)).thenReturn("{\"collections\":[],\"placements\":[],\"ingestJobs\":[]}");
        JsonNode response = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"get_knowledge_catalog\",\"arguments\":{\"spaceId\":7}}}");
        assertFalse(response.path("result").path("isError").asBoolean());
        assertTrue(response.path("result").path("content").get(0).path("text").asText().contains("placements"));
    }

    @Test
    void catalogWithoutSpaceIsAProtocolError() throws Exception {
        JsonNode response = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"get_knowledge_catalog\",\"arguments\":{}}}");
        assertEquals(-32602, response.path("error").path("code").asInt());
    }

    private JsonNode call(String body) throws Exception {
        McpDispatcher.Outcome outcome = dispatcher.handle(body, "test");
        return mapper.readTree(outcome.body());
    }

    @Test
    void historicalEvidenceForwardsExactVersionAndTimes() throws Exception {
        when(backend.videoEvidence(5L, 60000L, 120000L, 11L)).thenReturn("[{\"versionId\":11}]");
        var result = call("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_video_evidence",
                "arguments":{"mediaId":5,"startMs":60000,"endMs":120000,"versionId":11}}}
                """).path("result");
        assertFalse(result.path("isError").asBoolean());
        assertTrue(result.path("content").get(0).path("text").asText().contains("11"));
        verify(backend).videoEvidence(5L, 60000L, 120000L, 11L);
    }

    @Test
    void fractionalOrNonpositiveVersionCannotSelectAnotherEvidenceVersion() throws Exception {
        for (String version : new String[]{"11.9", "0", "-1", "\"11\""}) {
            var response = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"get_video_evidence\",\"arguments\":{\"mediaId\":5,\"versionId\":"+version+"}}}");
            assertEquals(-32602, response.path("error").path("code").asInt());
        }
        verifyNoInteractions(backend);
    }

    @Test
    void legacyBackendRejectsHistoricalRequestInsteadOfReturningCurrentEvidence() throws Exception {
        ToolBackend legacy = mock(ToolBackend.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        when(legacy.videoEvidence(5L,null,null)).thenReturn("current");
        assertEquals("current",legacy.videoEvidence(5L,null,null,null));
        assertThrows(UnsupportedOperationException.class,()->legacy.videoEvidence(5L,null,null,11L));
    }
}
