CREATE TABLE IF NOT EXISTS smd_event_store
(
    message_id          UUID PRIMARY KEY,
    event_type          VARCHAR(500)             NOT NULL,
    serialized_payload  BYTEA                    NOT NULL,
    serialized_metadata BYTEA                    NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    sequence_number     BIGSERIAL                NOT NULL UNIQUE
);

CREATE INDEX IF NOT EXISTS idx_smd_event_store_sequence
    ON smd_event_store (sequence_number ASC);

CREATE TABLE IF NOT EXISTS smd_token_store
(
    processing_group               VARCHAR(255) PRIMARY KEY,
    last_processed_message_id      UUID,
    last_processed_sequence_number BIGINT,
    last_failed_message_id         UUID,
    last_updated_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    error_count                    INTEGER DEFAULT 0,
    last_error_message             TEXT,
    last_error_at                  TIMESTAMP WITH TIME ZONE,
    last_gap_detected_at           TIMESTAMP WITH TIME ZONE,
    gap_sequence_number            BIGINT
);
