package com.example.mcp.server;

import com.example.mcp.audit.McpAuditWriter;
import com.example.mcp.client.ToolBackend;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Hand-written JSON-RPC 2.0 dispatcher implementing the stateless subset of the MCP
 * Streamable HTTP transport (2024-11-05 / 2025-03-26 / 2025-06-18): initialize,
 * notifications, ping, tools/list, tools/call. Every line here is deliberate —
 * no sessions, no SSE stream, exactly what a local read-only server needs.
 *
 * <p>Conventions: notifications produce no response; protocol errors are JSON-RPC
 * errors; tool <em>execution</em> failures are tool results with {@code isError:true}
 * so the assistant can see and relay them, per the MCP spec.</p>
 */
@Component
public class McpDispatcher {

    public static final String SERVER_NAME = "dovideo-knowledge";
    public static final String SERVER_VERSION = "0.2.0";
    public static final String LATEST_PROTOCOL_VERSION = "2025-06-18";
    private static final Set<String> SUPPORTED_PROTOCOL_VERSIONS =
            Set.of("2024-11-05", "2025-03-26", LATEST_PROTOCOL_VERSION);
    private static final int JSON_RPC_PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;

    /** HTTP outcome of one POST body: 200 with a JSON response, or 202 for notifications. */
    public record Outcome(int status, String body) {
        static final Outcome ACCEPTED = new Outcome(202, null);
    }

    private final ToolBackend backend;
    private final McpAuditWriter audit;
    private final ObjectMapper mapper;

    public McpDispatcher(ToolBackend backend, McpAuditWriter audit, ObjectMapper mapper) {
        this.backend = backend;
        this.audit = audit;
        this.mapper = mapper;
    }

    public Outcome handle(String rawBody, String clientLabel) {
        JsonNode root;
        try {
            root = mapper.readTree(rawBody == null || rawBody.isBlank() ? "" : rawBody);
        } catch (JsonProcessingException e) {
            return new Outcome(200, errorResponse(null, JSON_RPC_PARSE_ERROR, "Parse error").toString());
        }
        if (root == null || root.isNull() || !root.isObject() && !root.isArray()) {
            return new Outcome(200, errorResponse(null, INVALID_REQUEST, "Invalid Request").toString());
        }
        if (root.isArray()) {
            // JSON-RPC batch (used by some older clients): answer requests, drop notifications.
            ArrayNode responses = mapper.createArrayNode();
            for (JsonNode message : root) {
                JsonNode response = handleMessage(message, clientLabel);
                if (response != null) responses.add(response);
            }
            return responses.isEmpty()
                    ? Outcome.ACCEPTED
                    : new Outcome(200, responses.toString());
        }
        JsonNode response = handleMessage(root, clientLabel);
        return response == null ? Outcome.ACCEPTED : new Outcome(200, response.toString());
    }

    private JsonNode handleMessage(JsonNode message, String clientLabel) {
        if (!message.isObject() || !message.hasNonNull("method")) {
            return message.hasNonNull("id")
                    ? errorResponse(message.path("id"), INVALID_REQUEST, "Invalid Request")
                    : null;
        }
        String method = message.get("method").asText();
        JsonNode id = message.path("id");
        boolean isNotification = id.isMissingNode() || id.isNull();

        // Notifications are fire-and-forget in every MCP revision; never answer them.
        if (method.startsWith("notifications/")) return null;

        try {
            return switch (method) {
                case "initialize" -> isNotification ? null
                        : result(id, initializeResult(message.path("params").path("protocolVersion").asText(null)));
                case "ping" -> isNotification ? null : result(id, mapper.createObjectNode());
                case "tools/list" -> isNotification ? null : result(id, toolsListResult());
                case "tools/call" -> isNotification ? null : toolCall(id, message.path("params"), clientLabel);
                default -> isNotification ? null
                        : errorResponse(id, METHOD_NOT_FOUND, "Method not found: " + method);
            };
        } catch (InvalidParams e) {
            return errorResponse(id, INVALID_PARAMS, e.getMessage());
        }
    }

    private ObjectNode initializeResult(String clientProtocolVersion) {
        ObjectNode result = mapper.createObjectNode();
        // Version negotiation: echo a version we support, otherwise fall back to latest.
        result.put("protocolVersion", clientProtocolVersion != null
                && SUPPORTED_PROTOCOL_VERSIONS.contains(clientProtocolVersion)
                ? clientProtocolVersion : LATEST_PROTOCOL_VERSION);
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools").put("listChanged", false);
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("title", "DoVideo Personal Video Knowledge");
        serverInfo.put("version", SERVER_VERSION);
        result.put("instructions",
                "Read-only access to a personal video knowledge base. For questions, call "
                + "ask_video_knowledge: it returns a grounded natural-language answer with "
                + "server-verified citations (title, mediaId, timestamps, verbatim quote) or an "
                + "INSUFFICIENT_EVIDENCE refusal — always relay that refusal instead of guessing. "
                + "Use search_video_knowledge to browse raw evidence hits and get_video_evidence "
                + "to pull transcript/OCR around a timestamp. Cite mediaId + seconds back to the user.");
        return result;
    }

    private ObjectNode toolsListResult() {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode tools = result.putArray("tools");

        ObjectNode spaces = tools.addObject();
        spaces.put("name", "list_knowledge_spaces");
        spaces.put("description",
                "List the user's knowledge spaces (top-level video collections). "
                + "Use the ids with search_video_knowledge.");
        spaces.putObject("inputSchema").put("type", "object").putObject("properties");

        ObjectNode search = tools.addObject();
        search.put("name", "search_video_knowledge");
        search.put("description",
                "Search video evidence across all sources inside one knowledge space "
                + "(hybrid semantic + keyword by default). Returns hits with source title, "
                + "mediaId, startMs/endMs timestamps, match type and score. Read scope status and warnings before interpreting empty hits. Empty READY results mean "
                + "the corpus holds no supporting evidence — say so instead of guessing.");
        ObjectNode searchSchema = search.putObject("inputSchema");
        searchSchema.put("type", "object");
        ObjectNode searchProps = searchSchema.putObject("properties");
        searchProps.putObject("query").put("type", "string")
                .put("description", "Natural-language question or keywords");
        searchProps.putObject("spaceId").put("type", "integer")
                .put("description", "Space to search; omit to use the default space");
        searchProps.putObject("collectionId").put("type", "integer")
                .put("description", "Restrict to a folder and descendants; requires spaceId");
        searchProps.putObject("topK").put("type", "integer").put("minimum", 1).put("maximum", 20)
                .put("description", "Max hits to return (default 5)");
        ObjectNode strategy = searchProps.putObject("strategy");
        strategy.put("type", "string");
        strategy.putArray("enum").add("vector").add("keyword").add("hybrid");
        strategy.put("description", "Recall strategy (default hybrid)");
        searchSchema.putArray("required").add("query");

        ObjectNode ask = tools.addObject();
        ask.put("name", "ask_video_knowledge");
        ask.put("description",
                "Ask a natural-language question over the video knowledge base and get a "
                + "answer with citations whose original source, timestamps and verbatim quotes are checked "
                + "(source title, mediaId, millisecond timestamps, verbatim quote). Returns "
                + "answerability SUPPORTED with citations, NOT_READY while ingesting, or INSUFFICIENT_EVIDENCE when the "
                + "corpus cannot support an answer — relay that refusal instead of guessing. "
                + "Quote matching does not prove semantic support for a claim; inspect the original evidence.");
        ObjectNode askSchema = ask.putObject("inputSchema");
        askSchema.put("type", "object");
        ObjectNode askProps = askSchema.putObject("properties");
        askProps.putObject("query").put("type", "string")
                .put("description", "The question to answer from video evidence");
        askProps.putObject("spaceId").put("type", "integer")
                .put("description", "Knowledge space to ask against; omit to use the default space");
        askProps.putObject("collectionId").put("type", "integer")
                .put("description", "Narrow to one collection inside the space (optional)");
        askProps.putObject("topK").put("type", "integer").put("minimum", 1).put("maximum", 20)
                .put("description", "Evidence units exposed to the answer model (default 5)");
        ObjectNode askStrategy = askProps.putObject("strategy");
        askStrategy.put("type", "string");
        askStrategy.putArray("enum").add("vector").add("keyword").add("hybrid");
        askStrategy.put("description", "Recall strategy (default hybrid)");
        askSchema.putArray("required").add("query");

        ObjectNode evidence = tools.addObject();
        evidence.put("name", "get_video_evidence");
        evidence.put("description",
                "Fetch raw evidence rows of one video: transcript, OCR text and summary per "
                + "time window. Optionally narrow to a [startMs, endMs] range (e.g. around a "
                + "search hit) to keep the context small.");
        ObjectNode evidenceSchema = evidence.putObject("inputSchema");
        evidenceSchema.put("type", "object");
        ObjectNode evidenceProps = evidenceSchema.putObject("properties");
        evidenceProps.putObject("mediaId").put("type", "integer").put("description", "Video id from a search hit");
        evidenceProps.putObject("versionId").put("type", "integer").put("minimum", 1).put("description", "Exact published version from a citation; omit for current version");
        evidenceProps.putObject("startMs").put("type", "integer")
                .put("description", "Range start in milliseconds (optional)");
        evidenceProps.putObject("endMs").put("type", "integer")
                .put("description", "Range end in milliseconds (optional)");
        evidenceSchema.putArray("required").add("mediaId");
        ObjectNode catalog = tools.addObject();
        catalog.put("name", "get_knowledge_catalog");
        catalog.put("description", "Discover folders, shared source placements, published readiness and independent ingest jobs inside an authorized space.");
        ObjectNode catalogSchema = catalog.putObject("inputSchema");
        catalogSchema.put("type", "object");
        catalogSchema.putObject("properties").putObject("spaceId").put("type", "integer");
        catalogSchema.putArray("required").add("spaceId");

        return result;
    }

    private JsonNode toolCall(JsonNode id, JsonNode params, String clientLabel) {
        String tool = params.path("name").asText("");
        JsonNode args = params.path("arguments");
        long startedAt = System.nanoTime();
        try {
            String text = switch (tool) {
                case "list_knowledge_spaces" -> backend.listSpaces();
                case "search_video_knowledge" -> backend.searchKnowledge(
                        requiredString(args, "query", tool),
                        optionalLong(args, "spaceId"),
                        optionalLong(args, "collectionId"),
                        optionalInt(args, "topK"),
                        optionalString(args, "strategy"));
                case "ask_video_knowledge" -> backend.askKnowledge(
                        requiredString(args, "query", tool),
                        optionalLong(args, "spaceId"),
                        optionalLong(args, "collectionId"),
                        optionalInt(args, "topK"),
                        optionalString(args, "strategy"));
                case "get_knowledge_catalog" -> backend.knowledgeCatalog(requiredLong(args, "spaceId", tool));
                case "get_video_evidence" -> backend.videoEvidence(
                        requiredLong(args, "mediaId", tool),
                        optionalLong(args, "startMs"),
                        optionalLong(args, "endMs"),
                        optionalLong(args, "versionId"));
                default -> throw new InvalidParams("Unknown tool: " + tool);
            };
            audit.record(clientLabel, tool, true, elapsedMs(startedAt), null);
            ObjectNode result = mapper.createObjectNode();
            ArrayNode content = result.putArray("content");
            ObjectNode item = content.addObject();
            item.put("type", "text");
            item.put("text", text);
            return result(id, result);
        } catch (InvalidParams e) {
            // Protocol-level misuse of the tool contract: JSON-RPC error, not a tool result.
            return errorResponse(id, INVALID_PARAMS, e.getMessage());
        } catch (Exception e) {
            // Execution failure inside the tool: surface as isError result per MCP spec.
            audit.record(clientLabel, tool, false, elapsedMs(startedAt), e.getMessage());
            ObjectNode result = mapper.createObjectNode();
            result.put("isError", true);
            ArrayNode content = result.putArray("content");
            ObjectNode item = content.addObject();
            item.put("type", "text");
            item.put("text", "Tool execution failed: " + e.getMessage());
            return result(id, result);
        }
    }

    private static String requiredString(JsonNode args, String field, String tool) {
        String value = args.path(field).asText("");
        if (value.isBlank()) throw new InvalidParams(tool + " requires a non-empty '" + field + "'");
        return value;
    }

    private static Long requiredLong(JsonNode args, String field, String tool) {
        Long value = optionalLong(args, field);
        if (value == null || value <= 0) throw new InvalidParams(tool + " requires a positive '" + field + "'");
        return value;
    }

    private static Long optionalLong(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull()) {
            if ("mediaId".equals(field)) throw new InvalidParams("get_video_evidence requires 'mediaId'");
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong()) throw new InvalidParams("'" + field + "' must be an integer");
        if ("versionId".equals(field) && node.asLong() <= 0) throw new InvalidParams("'versionId' must be positive");
        return node.asLong();
    }

    private static Integer optionalInt(JsonNode args, String field) {
        Long value = optionalLong(args, field);
        if (value == null) return null;
        if (value < 1 || value > 20) throw new InvalidParams("'" + field + "' must be between 1 and 20");
        return value.intValue();
    }

    private static String optionalString(JsonNode args, String field) {
        JsonNode node = args.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    private ObjectNode result(JsonNode id, JsonNode result) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id.deepCopy());
        response.set("result", result);
        return response;
    }

    private ObjectNode errorResponse(JsonNode id, int code, String message) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id != null && !id.isMissingNode() && !id.isNull()) response.set("id", id.deepCopy());
        else response.putNull("id");
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }

    private static final class InvalidParams extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InvalidParams(String message) {
            super(message);
        }
    }
}
