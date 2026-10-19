package com.example.mcp.client;

/**
 * The read-only capabilities exposed as MCP tools. Implementations call the
 * authenticated DoVideo API; the protocol layer never depends on the transport.
 * Each method returns pre-formatted JSON text ready for an LLM tool result.
 */
public interface ToolBackend {

    /** Knowledge spaces the upstream account can see. */
    String listSpaces() throws Exception;

    /**
     * Cross-video evidence search inside one space. A null spaceId resolves to the
     * account's default space so casual assistant prompts still work.
     */
    String searchKnowledge(String query, Long spaceId, Long collectionId, Integer topK, String strategy) throws Exception;

    /**
     * Grounded cross-video RAG answer with server-verified citations. A null spaceId
     * resolves to the account's default space in the backend; readiness is returned
     * separately from answerability.
     */
    String askKnowledge(String query, Long spaceId, Long collectionId, Integer topK, String strategy)
            throws Exception;

    /** Directory tree, source placements, and independent knowledge-ingest status. */
    String knowledgeCatalog(Long spaceId) throws Exception;

    /** Raw evidence rows (transcript/OCR/summary per time window) of one media. */
    String videoEvidence(Long mediaId, Long startMs, Long endMs) throws Exception;
    default String videoEvidence(Long mediaId, Long startMs, Long endMs, Long versionId) throws Exception {
        if (versionId != null) throw new UnsupportedOperationException("Historical evidence versions are not supported by this backend");
        return videoEvidence(mediaId,startMs,endMs);
    }
}
