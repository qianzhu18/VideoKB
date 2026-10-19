-- Completion receipts for an immutable generation in a specific Milvus backend/profile.
-- A receipt is never sufficient by itself: query-time counts also detect lost index data.
CREATE TABLE knowledge_lexical_generations (
    backend_key CHAR(64) NOT NULL,
    version_id BIGINT NOT NULL,
    source_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    document_count INT NOT NULL,
    indexed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (backend_key, version_id),
    CONSTRAINT fk_lexical_version FOREIGN KEY (version_id) REFERENCES knowledge_source_versions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
