CREATE TABLE IF NOT EXISTS smd_deadline_store
(
    deadline_id         UUID PRIMARY KEY,
    command_type        VARCHAR(500)             NOT NULL,
    serialized_payload  BYTEA                    NOT NULL,
    serialized_metadata BYTEA                    NOT NULL,
    due_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    status              VARCHAR(32)              NOT NULL,
    error_count         INTEGER                  NOT NULL DEFAULT 0,
    last_error_message  TEXT,
    last_error_at       TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS smd_deadline_store_next_attempt
    ON smd_deadline_store (next_attempt_at)
    WHERE status <> 'ABANDONED';
