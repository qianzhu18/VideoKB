-- Content identity is independent of where it is filed. Preserve the old columns as
-- the primary-location compatibility projection; all browsing/search uses placements.
CREATE TABLE knowledge_placements (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source_id BIGINT NOT NULL,
    space_id BIGINT NOT NULL,
    collection_id BIGINT NULL,
    collection_key BIGINT GENERATED ALWAYS AS (COALESCE(collection_id, 0)) STORED,
    UNIQUE KEY uk_placement (source_id, space_id, collection_key),
    KEY idx_placement_scope (space_id, collection_id, source_id),
    FOREIGN KEY (source_id) REFERENCES knowledge_sources(id) ON DELETE CASCADE,
    FOREIGN KEY (space_id) REFERENCES knowledge_spaces(id) ON DELETE RESTRICT,
    FOREIGN KEY (collection_id) REFERENCES knowledge_collections(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO knowledge_placements (source_id, space_id, collection_id)
SELECT id, space_id, collection_id FROM knowledge_sources WHERE status <> 'DELETED';

-- This row is both the durable job and the outbox. MQ failure leaves it QUEUED.
CREATE TABLE knowledge_ingest_jobs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner_user_id BIGINT NOT NULL,
    source_id BIGINT NOT NULL,
    media_id BIGINT NOT NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    stage VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    attempt_count INT NOT NULL DEFAULT 0,
    error_message VARCHAR(1000) NULL,
    next_dispatch_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    heartbeat_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_ingest_media (media_id),
    KEY idx_ingest_dispatch (state, next_dispatch_at),
    KEY idx_ingest_owner (owner_user_id, updated_at),
    FOREIGN KEY (source_id) REFERENCES knowledge_sources(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO knowledge_ingest_jobs (owner_user_id, source_id, media_id)
SELECT owner_user_id, id, media_id FROM knowledge_sources
WHERE source_type = 'VIDEO' AND media_id IS NOT NULL AND status = 'PENDING';
