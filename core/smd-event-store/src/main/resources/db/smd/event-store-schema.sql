CREATE TABLE IF NOT EXISTS smd_event_store
(
    log                 VARCHAR(255)             NOT NULL DEFAULT 'events',
    message_id          UUID                     NOT NULL,
    subject_id          VARCHAR(500),
    event_type          VARCHAR(500)             NOT NULL,
    serialized_payload  BYTEA                    NOT NULL,
    serialized_metadata BYTEA                    NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    sequence_number     BIGSERIAL                NOT NULL UNIQUE,
    PRIMARY KEY (log, message_id)
);

CREATE INDEX IF NOT EXISTS smd_event_store_log_sequence_number ON smd_event_store (log, sequence_number);

CREATE TABLE IF NOT EXISTS smd_token_store
(
    log                            VARCHAR(255) NOT NULL DEFAULT 'events',
    processing_group               VARCHAR(255) NOT NULL,
    last_processed_sequence_number BIGINT,
    last_gap_detected_at           TIMESTAMP WITH TIME ZONE,
    gap_sequence_number            BIGINT,
    PRIMARY KEY (log, processing_group)
);

CREATE TABLE IF NOT EXISTS smd_event_sequence_state
(
    log                            VARCHAR(255)             NOT NULL DEFAULT 'events',
    processing_group               VARCHAR(255)             NOT NULL,
    subject_id                     VARCHAR(500),
    status                         VARCHAR(32)              NOT NULL,
    last_processed_sequence_number BIGINT,
    error_count                    INTEGER                  NOT NULL DEFAULT 0,
    last_error_message             TEXT,
    last_error_at                  TIMESTAMP WITH TIME ZONE,
    UNIQUE NULLS NOT DISTINCT (log, processing_group, subject_id)
);
