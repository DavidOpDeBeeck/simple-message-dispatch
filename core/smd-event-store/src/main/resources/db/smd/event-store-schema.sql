CREATE TABLE IF NOT EXISTS smd_event_store
(
    event_id          UUID PRIMARY KEY,
    aggregate_type    VARCHAR(255),
    aggregate_id      VARCHAR(500),
    aggregate_version BIGINT,
    event_type        VARCHAR(500)             NOT NULL,
    payload           BYTEA                    NOT NULL,
    metadata          BYTEA                    NOT NULL,
    occurred_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    global_position   BIGSERIAL                NOT NULL UNIQUE,
    CONSTRAINT smd_event_store_aggregate_revision_check
        CHECK ((aggregate_type IS NULL AND aggregate_version IS NULL)
            OR (aggregate_type IS NOT NULL AND aggregate_version IS NOT NULL AND aggregate_id IS NOT NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS smd_event_store_aggregate_stream
    ON smd_event_store (aggregate_type, aggregate_id, aggregate_version)
    WHERE aggregate_type IS NOT NULL;

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
    aggregate_id            VARCHAR(500),
    status                  VARCHAR(32)              NOT NULL,
    last_processed_position BIGINT,
    error_count             INTEGER                  NOT NULL DEFAULT 0,
    last_error_message      TEXT,
    last_error_at           TIMESTAMP WITH TIME ZONE,
    UNIQUE NULLS NOT DISTINCT (processing_group, aggregate_id)
);
