-- Durable ledger of analysis tasks: the persisted source of truth for the task
-- lifecycle. The Redis idempotency keys (active/attempts/completed) stay the
-- runtime authority, but once their TTLs expire there is nothing to reconcile
-- against. This table records every state transition keyed by
-- (media_id, goal_digest), backing the manifest view, failure recovery and the
-- "a new batch must not re-run already-succeeded videos" audit. Writes are
-- best-effort: a ledger outage must never block the analysis pipeline
-- (see AnalysisTaskService).
CREATE TABLE IF NOT EXISTS analysis_tasks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_user_id BIGINT NOT NULL,
    media_id BIGINT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    goal_digest CHAR(64) NOT NULL,
    mode VARCHAR(32) NOT NULL DEFAULT 'GENERAL',
    state VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_stage VARCHAR(64) NULL,
    error_type VARCHAR(128) NULL,
    error_message VARCHAR(1000) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_analysis_task_media_goal (media_id, goal_digest),
    KEY idx_analysis_task_owner_time (owner_user_id, updated_at),
    KEY idx_analysis_task_state_time (state, updated_at),
    KEY idx_analysis_task_content (content_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
