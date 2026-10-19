package com.example.mcp.server;

import com.example.mcp.config.McpProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * The single MCP transport endpoint (Streamable HTTP, stateless subset): POST only.
 * GET/DELETE answer 405 — this server offers no SSE stream and no sessions, which
 * the MCP spec permits for servers without server-initiated communication.
 */
@RestController
public class McpEndpoint {

    private final McpDispatcher dispatcher;
    private final List<String> clientTokens;

    public McpEndpoint(McpDispatcher dispatcher, McpProperties properties) {
        this.dispatcher = dispatcher;
        this.clientTokens = properties.clientTokens() == null ? List.of() : properties.clientTokens();
    }

    @PostMapping(value = "/mcp", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> post(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody String body) {
        String clientToken = bearerToken(authorization);
        if (clientToken == null || !matchesAnyConfiguredToken(clientToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header("WWW-Authenticate", "Bearer")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32001,\"message\":\"unauthorized\"}}");
        }
        McpDispatcher.Outcome outcome = dispatcher.handle(body, clientLabel(clientToken));
        if (outcome.body() == null) {
            return ResponseEntity.status(outcome.status()).build();
        }
        return ResponseEntity.status(outcome.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(outcome.body());
    }

    @GetMapping("/mcp")
    public ResponseEntity<Void> get() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .allow(org.springframework.http.HttpMethod.POST)
                .build();
    }

    @DeleteMapping("/mcp")
    public ResponseEntity<Void> delete() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    private static String bearerToken(String authorization) {
        if (authorization == null) return null;
        String prefix = "Bearer ";
        return authorization.startsWith(prefix) ? authorization.substring(prefix.length()).trim() : null;
    }

    /** Constant-time comparisons so token probing cannot be timed. */
    private boolean matchesAnyConfiguredToken(String candidate) {
        byte[] candidateBytes = candidate.getBytes(StandardCharsets.UTF_8);
        for (String configured : clientTokens) {
            if (configured == null || configured.isBlank()) continue;
            if (MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8), candidateBytes)) {
                return true;
            }
        }
        return false;
    }

    /** Audit identity: a stable prefix of the token digest, never the raw token. */
    private static String clientLabel(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return "client-" + HexFormat.of().formatHex(digest, 0, 4);
        } catch (Exception e) {
            return "client-unknown";
        }
    }
}
