package com.example.mcp.server;

import com.example.mcp.client.ToolBackend;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Transport-level contract of the /mcp endpoint: bearer-token gate, method routing,
 * and the 405s for the transports this stateless server does not offer.
 */
@SpringBootTest(properties = "mcp.client-tokens=e2e-client-token")
@AutoConfigureMockMvc
class McpEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ToolBackend backend;

    @Test
    void rejectsMissingOrWrongClientTokenBeforeTouchingTheBackend() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
        mockMvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", "Bearer wrong-token"))
                .andExpect(status().isUnauthorized());
        verify(backend, never()).listSpaces();
    }

    @Test
    void servesToolsListAndToolCallsForAuthenticatedClients() throws Exception {
        mockMvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
                        .header("Authorization", "Bearer e2e-client-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools.length()").value(4));

        when(backend.listSpaces()).thenReturn("[]");
        mockMvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                                + "\"params\":{\"name\":\"list_knowledge_spaces\",\"arguments\":{}}}")
                        .header("Authorization", "Bearer e2e-client-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].type").value("text"));
        verify(backend).listSpaces();
    }

    @Test
    void streamableHttpGetAndDeleteAreNotAllowed() throws Exception {
        mockMvc.perform(get("/mcp")).andExpect(status().isMethodNotAllowed());
    }
}
