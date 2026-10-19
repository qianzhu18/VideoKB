-- Distinguish retrying delivery from explicitly rebuilding an already-published source.
ALTER TABLE knowledge_ingest_jobs ADD COLUMN force_rebuild BOOLEAN NOT NULL DEFAULT FALSE AFTER attempt_count;
