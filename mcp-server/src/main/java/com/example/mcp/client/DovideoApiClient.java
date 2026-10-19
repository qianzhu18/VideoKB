package com.example.mcp.client;

import com.example.mcp.config.McpProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * HTTP client for the DoVideo Spring Boot API — the ONLY way this adapter reaches data.
 * Upstream auth: a configured session token if provided, otherwise username/password
 * login with the session cached and one transparent re-login on 40100 expiry.
 */
@Component
public class DovideoApiClient implements ToolBackend {

    private static final Logger log = LoggerFactory.getLogger(DovideoApiClient.class);
    private static final int UPSTREAM_SESSION_EXPIRED = 40100;
    /** LLM context hygiene: cap each text field so one huge transcript cannot flood the assistant. */
    private static final int MAX_TEXT_FIELD_CHARS = 2000;
    private static final int MAX_EVIDENCE_ROWS = 50;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String staticToken;
    private final String username;
    private final String password;
    private volatile String sessionToken;

    public DovideoApiClient(McpProperties properties, ObjectMapper mapper) {
        McpProperties.Upstream upstream = properties.upstream();
        this.mapper = mapper;
        this.baseUrl = upstream.baseUrl().replaceAll("/+$", "");
        this.staticToken = blankToNull(upstream.token());
        this.username = blankToNull(upstream.username());
        this.password = blankToNull(upstream.password());
    }

    @Override
    public String listSpaces() throws Exception {
        return compactSpaces(call("GET", "/knowledge/spaces", null, true));
    }

    @Override
    public String searchKnowledge(String query, Long spaceId, Long collectionId, Integer topK, String strategy) throws Exception {
        if (collectionId != null && spaceId == null) throw new IllegalArgumentException("collectionId requires spaceId");
        ObjectNode request = mapper.createObjectNode();
        request.put("query", query);
        if (spaceId != null) request.put("spaceId", spaceId);
        if (collectionId != null) request.put("collectionId", collectionId);
        if (topK != null) request.put("topK", topK);
        if (strategy != null && !strategy.isBlank()) request.put("strategy", strategy);
        JsonNode data = call("POST", "/knowledge/search/details", request.toString(), true);
        ObjectNode result = mapper.createObjectNode();
        result.set("scope", data.path("scope"));
        result.set("warnings", data.path("warnings"));
        ArrayNode hits = result.putArray("hits");
        for (JsonNode hit : data.path("hits")) {
            ObjectNode item = hit.deepCopy();
            item.put("startSec", hit.path("startMs").asLong() / 1000);
            item.put("endSec", hit.path("endMs").asLong() / 1000);
            putTrimmed(item, "transcript", hit.path("transcript"));
            putTrimmed(item, "ocrText", hit.path("ocrText"));
            putTrimmed(item, "summary", hit.path("summary"));
            if (item.has("evidence")) for (JsonNode raw : item.path("evidence")) {
                putTrimmed((ObjectNode)raw,"transcript",raw.path("transcript"));
                putTrimmed((ObjectNode)raw,"ocrText",raw.path("ocrText"));
            }
            hits.add(item);
        }
        return result.toString();
    }

    @Override
    public String askKnowledge(String query, Long spaceId, Long collectionId, Integer topK, String strategy)
            throws Exception {
        if (collectionId != null && spaceId == null) throw new IllegalArgumentException("collectionId requires spaceId");
        ObjectNode request = mapper.createObjectNode();
        request.put("query", query);
        if (spaceId != null) request.put("spaceId", spaceId);
        if (collectionId != null) request.put("collectionId", collectionId);
        if (topK != null) request.put("topK", topK);
        if (strategy != null && !strategy.isBlank()) request.put("strategy", strategy);
        return compactAnswer(call("POST", "/knowledge/ask", request.toString(), true));
    }

    @Override
    public String knowledgeCatalog(Long spaceId) throws Exception {
        if (spaceId == null) throw new IllegalArgumentException("knowledge catalog requires spaceId");
        return call("GET", "/knowledge/spaces/" + spaceId + "/catalog", null, true).toString();
    }

    @Override
    public String videoEvidence(Long mediaId, Long startMs, Long endMs) throws Exception {
        return videoEvidence(mediaId,startMs,endMs,null);
    }

    @Override
    public String videoEvidence(Long mediaId, Long startMs, Long endMs, Long versionId) throws Exception {
        JsonNode segments = call("GET", "/knowledge/sources/media/" + mediaId + "/segments"
                + (versionId == null ? "" : "?versionId=" + versionId), null, true);
        return compactSegments(segments, startMs, endMs);
    }

    /**
     * Executes one API call and unwraps the {code,message,data} envelope. A single
     * expired-session retry is attempted in password mode; static-token mode fails fast.
     */
    private JsonNode call(String method, String path, String jsonBody, boolean retryOnExpired) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + currentToken());
        if (jsonBody != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode envelope = mapper.readTree(response.body() == null ? "" : response.body());
        int code = envelope.path("code").asInt(-1);
        if (code == 0) return envelope.get("data");

        if (code == UPSTREAM_SESSION_EXPIRED && retryOnExpired && staticToken == null && username != null) {
            this.sessionToken = null; // force re-login on the next currentToken()
            log.info("dovideo_session_expired_relogin user={}", username);
            return call(method, path, jsonBody, false);
        }
        throw new IOException("DoVideo API " + method + " " + path + " failed: code=" + code
                + " message=" + envelope.path("message").asText(""));
    }

    private String currentToken() throws Exception {
        if (staticToken != null) return staticToken;
        if (sessionToken != null) return sessionToken;
        if (username == null || password == null) {
            throw new IOException("Upstream auth not configured: set DOVIDEO_API_TOKEN or DOVIDEO_API_USERNAME/PASSWORD");
        }
        ObjectNode login = mapper.createObjectNode();
        login.put("username", username);
        login.put("password", password);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/user/login"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(login.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode envelope = mapper.readTree(response.body());
        if (envelope.path("code").asInt(-1) != 0) {
            throw new IOException("DoVideo login failed: " + envelope.path("message").asText(""));
        }
        sessionToken = envelope.path("data").path("token").asText(null);
        if (sessionToken == null) throw new IOException("DoVideo login returned no token");
        return sessionToken;
    }

    /**
     * Narrows the upstream answer to what an assistant needs: the full answer text
     * (it is the payload, never truncated), citations with second-precision aliases,
     * and quotes capped like every other text field.
     */
    private String compactAnswer(JsonNode data) {
        ObjectNode out = mapper.createObjectNode();
        out.put("answerability", data.path("answerability").asText("INSUFFICIENT_EVIDENCE"));
        out.put("answer", data.path("answer").asText(""));
        if (data.has("scope")) out.set("scope", data.get("scope"));
        ArrayNode citations = out.putArray("citations");
        for (JsonNode citation : data.path("citations").isArray()
                ? data.path("citations") : mapper.createArrayNode()) {
            ObjectNode item = citations.addObject();
            item.put("segmentId", citation.path("segmentId").asText());
            if (citation.has("versionId")) item.set("versionId",citation.get("versionId"));
            item.put("title", citation.path("title").asText());
            if (citation.path("mediaId").isMissingNode() || citation.path("mediaId").isNull()) {
                item.putNull("mediaId");
            } else {
                item.put("mediaId", citation.path("mediaId").asLong());
            }
            long startMs = citation.path("startMs").asLong(0);
            long endMs = citation.path("endMs").asLong(0);
            item.put("startMs", startMs);
            item.put("endMs", endMs);
            item.put("startSec", startMs / 1000);
            item.put("endSec", endMs / 1000);
            putTrimmed(item, "claim", citation.path("claim"));
            putTrimmed(item, "quote", citation.path("quote"));
        }
        ArrayNode warnings = out.putArray("warnings");
        for (JsonNode warning : data.path("warnings").isArray()
                ? data.path("warnings") : mapper.createArrayNode()) {
            warnings.add(warning.asText());
        }
        return out.toString();
    }

    private String compactSpaces(JsonNode data) {
        ArrayNode out = mapper.createArrayNode();
        for (JsonNode space : data.isArray() ? data : mapper.createArrayNode()) {
            ObjectNode item = out.addObject();
            item.put("id", space.path("id").asLong());
            item.put("name", space.path("name").asText());
            if (!space.path("description").isNull()) item.put("description", space.path("description").asText());
            item.put("systemDefault", space.path("systemDefault").asBoolean(false));
        }
        return out.toString();
    }

    private String compactSegments(JsonNode data, Long startMs, Long endMs) {
        ArrayNode out = mapper.createArrayNode();
        int included = 0;
        for (JsonNode segment : data.isArray() ? data : mapper.createArrayNode()) {
            long segStart = segment.path("startMs").asLong(0);
            long segEnd = segment.path("endMs").asLong(0);
            boolean overlaps = startMs == null || endMs == null
                    || (segStart < endMs && segEnd > startMs);
            if (!overlaps || included >= MAX_EVIDENCE_ROWS) continue;
            ObjectNode item = out.addObject();
            item.put("startMs", segStart);
            item.put("segmentId",segment.path("id").asText());
            if (segment.has("versionId")) item.set("versionId",segment.get("versionId"));
            if (segment.has("sourceId")) item.set("sourceId",segment.get("sourceId"));
            item.put("endMs", segEnd);
            item.put("startSec", segStart / 1000);
            item.put("endSec", segEnd / 1000);
            putTrimmed(item, "transcript", segment.path("transcript"));
            putTrimmed(item, "ocrText", segment.path("ocrText"));
            putTrimmed(item, "summary", segment.path("summary"));
            included++;
        }
        return out.toString();
    }

    private void putTrimmed(ObjectNode target, String field, JsonNode source) {
        if (source == null || source.isNull()) return;
        String value = source.asText("");
        if (!value.isBlank()) target.put(field, trim(value, MAX_TEXT_FIELD_CHARS));
    }

    private static String firstNonBlank(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && !node.isNull() && !node.asText("").isBlank()) return node.asText();
        }
        return null;
    }

    private static String trim(String value, int max) {
        String normalized = value.strip();
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "…[truncated]";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
