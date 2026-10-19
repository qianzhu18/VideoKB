package com.example.mcp.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Append-only JSONL audit trail for MCP tool calls, following the design of the
 * upstream knowledge_audit_logs (who / what / when / outcome). Audit failure must
 * never break a tool call — it degrades to a warning.
 */
@Component
public class McpAuditWriter {

    private static final Logger log = LoggerFactory.getLogger(McpAuditWriter.class);

    private final Path auditPath;
    private final ObjectMapper objectMapper;

    public McpAuditWriter(@Value("${mcp.audit.path:data/mcp-audit.jsonl}") String auditPath,
                          ObjectMapper objectMapper) {
        this.auditPath = Path.of(auditPath);
        this.objectMapper = objectMapper;
    }

    public void record(String clientLabel, String tool, boolean ok, long durationMs, String detail) {
        try {
            Files.createDirectories(auditPath.toAbsolutePath().getParent());
            ObjectNode entry = objectMapper.createObjectNode();
            entry.put("ts", System.currentTimeMillis());
            entry.put("client", clientLabel);
            entry.put("tool", tool);
            entry.put("ok", ok);
            entry.put("durationMs", durationMs);
            if (detail != null) entry.put("detail", abbreviate(detail));
            synchronized (this) {
                Files.writeString(auditPath,
                        entry + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            log.warn("mcp_audit_write_failed path={} tool={}", auditPath, tool, e);
        }
    }

    /** Keep the audit row bounded; tool outputs live in the upstream logs. */
    private static String abbreviate(String value) {
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200) + "…";
    }
}
