-- Original evidence remains immutable; blocks are derived and published with a generation.
ALTER TABLE knowledge_source_versions
    ADD COLUMN index_profile VARCHAR(96) NOT NULL DEFAULT 'legacy-v1',
    ADD COLUMN vector_dimension INT NULL;
CREATE TABLE knowledge_retrieval_blocks (
    id CHAR(36) NOT NULL PRIMARY KEY,
    source_id BIGINT NOT NULL,
    version_id BIGINT NOT NULL,
    media_id BIGINT NULL,
    start_ms BIGINT NOT NULL,
    end_ms BIGINT NOT NULL,
    text MEDIUMTEXT NOT NULL,
    index_profile VARCHAR(96) NOT NULL,
    text_hash CHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_block_version(version_id),
    CONSTRAINT fk_block_version FOREIGN KEY (version_id) REFERENCES knowledge_source_versions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE knowledge_block_evidence (
    block_id CHAR(36) NOT NULL,
    evidence_id CHAR(36) NOT NULL,
    ordinal_no INT NOT NULL,
    PRIMARY KEY (block_id,evidence_id),
    INDEX idx_block_evidence_raw(evidence_id),
    CONSTRAINT fk_block_evidence_block FOREIGN KEY (block_id) REFERENCES knowledge_retrieval_blocks(id) ON DELETE CASCADE,
    CONSTRAINT fk_block_evidence_raw FOREIGN KEY (evidence_id) REFERENCES knowledge_segments(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE knowledge_embedding_cache (
    cache_key CHAR(64) NOT NULL PRIMARY KEY,
    owner_user_id BIGINT NOT NULL,
    model VARCHAR(255) NOT NULL,
    index_profile VARCHAR(96) NOT NULL,
    text_hash CHAR(64) NOT NULL,
    vector_dimension INT NOT NULL,
    vector_json MEDIUMTEXT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_vector_cache_owner(owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
