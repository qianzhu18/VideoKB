package com.example.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Adapter configuration. Two trust boundaries:
 * client-tokens authenticate MCP clients (the assistant side); upstream credentials
 * authenticate this adapter toward the DoVideo API (the data side).
 */
@ConfigurationProperties(prefix = "mcp")
public record McpProperties(List<String> clientTokens, Upstream upstream, Audit audit) {

    public record Upstream(String baseUrl, String token, String username, String password) {
    }

    public record Audit(String path) {
    }
}
