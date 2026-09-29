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
    void toolsListExposesExactlyTheFourReadOnlyTools() throws Exception {
        JsonNode tools = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
                .path("result").path("tools");
        assertEquals(4, tools.size());
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
        when(backend.searchKnowledge(eq("浏阳河"), isNull(), isNull(), isNull()))
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

    private JsonNode call(String body) throws Exception {
        McpDispatcher.Outcome outcome = dispatcher.handle(body, "test");
        return mapper.readTree(outcome.body());
    }
}
