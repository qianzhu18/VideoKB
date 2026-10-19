CREATE TABLE knowledge_audit_logs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_user_id BIGINT NOT NULL,
    action VARCHAR(64) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_id BIGINT NULL,
    space_id BIGINT NULL,
    collection_id BIGINT NULL,
    details VARCHAR(1000) NOT NULL DEFAULT '',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_knowledge_audit_owner_created (owner_user_id, created_at),
    KEY idx_knowledge_audit_resource (resource_type, resource_id, created_at),
    CONSTRAINT fk_knowledge_audit_owner
        FOREIGN KEY (owner_user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
