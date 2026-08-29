CREATE TABLE smd_event_store
(
    message_id          UUID PRIMARY KEY,
    subject_id          VARCHAR(500),
    event_type          VARCHAR(500)             NOT NULL,
    serialized_payload  BYTEA                    NOT NULL,
    serialized_metadata BYTEA                    NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    sequence_number     BIGSERIAL                NOT NULL UNIQUE
);

CREATE TABLE smd_token_store
(
    processing_group               VARCHAR(255) PRIMARY KEY,
    last_processed_sequence_number BIGINT,
    last_gap_detected_at           TIMESTAMP WITH TIME ZONE,
    gap_sequence_number            BIGINT
);

CREATE TABLE smd_event_sequence_state
(
    processing_group               VARCHAR(255) NOT NULL,
    subject_id                     VARCHAR(500),
    status                         VARCHAR(32)  NOT NULL,
    last_processed_sequence_number BIGINT,
    error_count                    INTEGER      NOT NULL DEFAULT 0,
    last_error_message             TEXT,
    last_error_at                  TIMESTAMP WITH TIME ZONE,
    UNIQUE NULLS NOT DISTINCT (processing_group, subject_id)
);

CREATE TABLE ticket
(
    ticket_id   UUID PRIMARY KEY,
    title       VARCHAR(200)             NOT NULL,
    description TEXT                     NOT NULL,
    status      VARCHAR(32)              NOT NULL,
    assignee    VARCHAR(200),
    resolution  TEXT,
    version     INTEGER                  NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE ticket_view
(
    ticket_id   UUID PRIMARY KEY,
    title       VARCHAR(200)             NOT NULL,
    description TEXT                     NOT NULL,
    status      VARCHAR(32)              NOT NULL,
    assignee    VARCHAR(200),
    resolution  TEXT,
    opened_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    assigned_at TIMESTAMP WITH TIME ZONE,
    resolved_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE ticket_activity
(
    ticket_id   UUID                     NOT NULL,
    event_id    UUID PRIMARY KEY,
    event_type  VARCHAR(100)             NOT NULL,
    customer_id VARCHAR(200),
    timestamp   TIMESTAMP WITH TIME ZONE NOT NULL,
    details     TEXT                     NOT NULL
);
