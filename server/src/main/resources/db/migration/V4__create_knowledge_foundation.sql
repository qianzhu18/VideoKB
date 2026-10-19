CREATE TABLE knowledge_spaces (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_user_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    is_system_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_space_owner_name (owner_user_id, name),
    KEY idx_knowledge_space_owner_updated (owner_user_id, updated_at),
    CONSTRAINT fk_knowledge_space_owner
        FOREIGN KEY (owner_user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE knowledge_collections (
    id BIGINT NOT NULL AUTO_INCREMENT,
    space_id BIGINT NOT NULL,
    parent_id BIGINT NULL,
    name VARCHAR(100) NOT NULL,
    path VARCHAR(700) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_collection_path (space_id, path),
    KEY idx_knowledge_collection_parent (space_id, parent_id, sort_order),
    CONSTRAINT fk_knowledge_collection_space
        FOREIGN KEY (space_id) REFERENCES knowledge_spaces(id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_collection_parent
        FOREIGN KEY (parent_id) REFERENCES knowledge_collections(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE knowledge_sources (
    id BIGINT NOT NULL AUTO_INCREMENT,
    source_type VARCHAR(16) NOT NULL,
    owner_user_id BIGINT NOT NULL,
    space_id BIGINT NOT NULL,
    collection_id BIGINT NULL,
    media_id BIGINT NULL,
    title VARCHAR(255) NOT NULL,
    content_hash VARCHAR(64) NULL,
    current_version INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    external_path VARCHAR(2048) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_source_media (media_id),
    KEY idx_knowledge_source_owner_space_status (owner_user_id, space_id, status),
    KEY idx_knowledge_source_collection (collection_id),
    CONSTRAINT fk_knowledge_source_owner
        FOREIGN KEY (owner_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_source_space
        FOREIGN KEY (space_id) REFERENCES knowledge_spaces(id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_source_collection
        FOREIGN KEY (collection_id) REFERENCES knowledge_collections(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE knowledge_source_versions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    source_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    content_hash VARCHAR(64) NULL,
    parser_version VARCHAR(64) NULL,
    embedding_model VARCHAR(128) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    failure_reason VARCHAR(1000) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_source_version (source_id, version_no),
    KEY idx_knowledge_source_version_status (status, updated_at),
    CONSTRAINT fk_knowledge_source_version_source
        FOREIGN KEY (source_id) REFERENCES knowledge_sources(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE knowledge_segments (
    id CHAR(36) NOT NULL,
    source_id BIGINT NOT NULL,
    version_id BIGINT NOT NULL,
    media_id BIGINT NULL,
    start_ms BIGINT NULL,
    end_ms BIGINT NULL,
    transcript LONGTEXT NULL,
    ocr_text LONGTEXT NULL,
    summary TEXT NULL,
    content_hash VARCHAR(64) NULL,
    metadata JSON NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_knowledge_segment_source_version (source_id, version_id),
    KEY idx_knowledge_segment_media_time (media_id, start_ms, end_ms),
    CONSTRAINT fk_knowledge_segment_source
        FOREIGN KEY (source_id) REFERENCES knowledge_sources(id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_segment_version
        FOREIGN KEY (version_id) REFERENCES knowledge_source_versions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE knowledge_links (
    id BIGINT NOT NULL AUTO_INCREMENT,
    source_id BIGINT NOT NULL,
    target_source_id BIGINT NOT NULL,
    source_segment_id CHAR(36) NULL,
    target_segment_id CHAR(36) NULL,
    link_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'SUGGESTED',
    confidence DECIMAL(5,4) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_knowledge_link_source_status (source_id, status),
    KEY idx_knowledge_link_target_status (target_source_id, status),
    CONSTRAINT fk_knowledge_link_source
        FOREIGN KEY (source_id) REFERENCES knowledge_sources(id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_link_target_source
        FOREIGN KEY (target_source_id) REFERENCES knowledge_sources(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO knowledge_spaces (owner_user_id, name, description, is_system_default)
SELECT u.id, '未分类', '由系统为已有视频创建的默认知识空间', TRUE
FROM users u
WHERE NOT EXISTS (
    SELECT 1
    FROM knowledge_spaces s
    WHERE s.owner_user_id = u.id AND s.is_system_default = TRUE
);

INSERT INTO knowledge_sources (
    source_type, owner_user_id, space_id, media_id, title, content_hash, current_version, status
)
SELECT 'VIDEO', m.user_id, s.id, m.id, m.filename, m.content_hash, 1, 'PENDING'
FROM media_files m
JOIN knowledge_spaces s ON s.owner_user_id = m.user_id AND s.is_system_default = TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM knowledge_sources source WHERE source.media_id = m.id
);

INSERT INTO knowledge_source_versions (source_id, version_no, content_hash, status)
SELECT source.id, 1, source.content_hash, 'PENDING'
FROM knowledge_sources source
WHERE source.current_version = 1
  AND NOT EXISTS (
      SELECT 1
      FROM knowledge_source_versions version_row
      WHERE version_row.source_id = source.id AND version_row.version_no = 1
  );
