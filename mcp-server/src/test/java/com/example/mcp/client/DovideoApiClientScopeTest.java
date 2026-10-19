package com.example.mcp.client;

import com.example.mcp.config.McpProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class DovideoApiClientScopeTest {
    @Test void omittedScopeIsResolvedByBackendForBothToolsAndNotReadySurvives() throws Exception {
        var mapper = new ObjectMapper(); var paths = new ArrayList<String>(); var bodies = new ArrayList<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            bodies.add(mapper.readTree(exchange.getRequestBody()));
            String data = exchange.getRequestURI().getPath().endsWith("details")
                    ? "{\"scope\":{\"spaceId\":7,\"status\":\"NOT_READY\"},\"hits\":[],\"warnings\":[\"资料未就绪\"]}"
                    : "{\"scope\":{\"spaceId\":7,\"status\":\"NOT_READY\"},\"answerability\":\"NOT_READY\",\"answer\":\"资料未就绪\",\"citations\":[],\"warnings\":[\"资料未就绪\"]}";
            byte[] bytes = ("{\"code\":0,\"data\":" + data + "}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var props = new McpProperties(java.util.List.of("client-test"), new McpProperties.Upstream(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-token", null, null), null);
            var client = new DovideoApiClient(props, mapper);
            var hits = mapper.readTree(client.searchKnowledge("缓存", null, null, 8, "hybrid"));
            var answer = mapper.readTree(client.askKnowledge("缓存", null, null, 8, "hybrid"));
            assertEquals(java.util.List.of("/knowledge/search/details", "/knowledge/ask"), paths);
            assertTrue(bodies.stream().noneMatch(b -> b.has("spaceId")));
            assertEquals("NOT_READY", hits.path("scope").path("status").asText());
            assertEquals("NOT_READY", answer.path("answerability").asText());
            assertEquals(7, answer.path("scope").path("spaceId").asLong());
            assertThrows(IllegalArgumentException.class, () -> client.searchKnowledge("缓存", null, 5L, 8, null));
            assertThrows(IllegalArgumentException.class, () -> client.askKnowledge("缓存", null, 5L, 8, null));
            client.searchKnowledge("缓存", 9L, 12L, 8, null);
            assertEquals(9, bodies.get(2).path("spaceId").asLong());
            assertEquals(12, bodies.get(2).path("collectionId").asLong());
        } finally { server.stop(0); }
    }
}
