CREATE TABLE IF NOT EXISTS smd_event_store
(
    message_id          UUID PRIMARY KEY,
    subject_id          VARCHAR(500),
    event_type          VARCHAR(500)             NOT NULL,
    serialized_payload  BYTEA                    NOT NULL,
    serialized_metadata BYTEA                    NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    sequence_number     BIGSERIAL                NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS smd_token_store
(
    processing_group               VARCHAR(255) PRIMARY KEY,
    last_processed_sequence_number BIGINT,
    last_gap_detected_at           TIMESTAMP WITH TIME ZONE,
    gap_sequence_number            BIGINT
);

CREATE TABLE IF NOT EXISTS smd_event_sequence_state
(
    processing_group               VARCHAR(255)             NOT NULL,
    subject_id                     VARCHAR(500),
    status                         VARCHAR(32)              NOT NULL,
    last_processed_sequence_number BIGINT,
    error_count                    INTEGER                  NOT NULL DEFAULT 0,
    last_error_message             TEXT,
    last_error_at                  TIMESTAMP WITH TIME ZONE,
    UNIQUE NULLS NOT DISTINCT (processing_group, subject_id)
);
