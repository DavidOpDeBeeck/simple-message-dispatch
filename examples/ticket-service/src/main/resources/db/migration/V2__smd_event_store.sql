CREATE TABLE IF NOT EXISTS smd_event_store
(
    event_id        UUID PRIMARY KEY,
    subject_type    VARCHAR(255),
    subject_id      VARCHAR(500),
    subject_version BIGINT,
    event_type      VARCHAR(500)             NOT NULL,
    payload         BYTEA                    NOT NULL,
    metadata        BYTEA                    NOT NULL,
    occurred_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    global_position BIGSERIAL                NOT NULL UNIQUE,
    CONSTRAINT smd_event_store_subject_check CHECK ((subject_type IS NULL) = (subject_id IS NULL)),
    CONSTRAINT smd_event_store_subject_version_check CHECK (subject_version IS NULL OR subject_id IS NOT NULL)
);

CREATE UNIQUE INDEX IF NOT EXISTS smd_event_store_subject_stream
    ON smd_event_store (subject_type, subject_id, subject_version)
    WHERE subject_version IS NOT NULL;

CREATE TABLE IF NOT EXISTS smd_token_store
(
    processing_group        VARCHAR(255) PRIMARY KEY,
    last_processed_position BIGINT,
    last_gap_detected_at    TIMESTAMP WITH TIME ZONE,
    gap_position            BIGINT
);

CREATE TABLE IF NOT EXISTS smd_event_sequence_state
(
    processing_group        VARCHAR(255)             NOT NULL,
    subject_type            VARCHAR(255),
    subject_id              VARCHAR(500),
    status                  VARCHAR(32)              NOT NULL,
    last_processed_position BIGINT,
    error_count             INTEGER                  NOT NULL DEFAULT 0,
    last_error_message      TEXT,
    last_error_at           TIMESTAMP WITH TIME ZONE,
    CONSTRAINT smd_event_sequence_state_sequence_key UNIQUE NULLS NOT DISTINCT (processing_group, subject_type, subject_id),
    CONSTRAINT smd_event_sequence_state_subject_check CHECK ((subject_type IS NULL) = (subject_id IS NULL))
);
