CREATE TABLE knowledge_ingest_scans (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_user_id BIGINT NOT NULL,
    root_path VARCHAR(2048) NOT NULL,
    dry_run BOOLEAN NOT NULL DEFAULT TRUE,
    created_count INT NOT NULL DEFAULT 0,
    changed_count INT NOT NULL DEFAULT 0,
    moved_count INT NOT NULL DEFAULT 0,
    deleted_count INT NOT NULL DEFAULT 0,
    unchanged_count INT NOT NULL DEFAULT 0,
    error_count INT NOT NULL DEFAULT 0,
    plan JSON NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_knowledge_ingest_scan_owner (owner_user_id, created_at),
    CONSTRAINT fk_knowledge_ingest_scan_owner
        FOREIGN KEY (owner_user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
