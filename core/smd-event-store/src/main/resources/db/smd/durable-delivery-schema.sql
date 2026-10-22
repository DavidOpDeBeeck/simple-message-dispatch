CREATE TABLE IF NOT EXISTS smd_outbox_sequence
(
    outbox        VARCHAR(255) NOT NULL,
    subject_id    VARCHAR(500) NOT NULL,
    last_position BIGINT       NOT NULL,
    PRIMARY KEY (outbox, subject_id)
);

CREATE TABLE IF NOT EXISTS smd_outbox
(
    id                  BIGSERIAL PRIMARY KEY,
    outbox              VARCHAR(255)             NOT NULL,
    message_id          UUID                     NOT NULL,
    subject_id          VARCHAR(500),
    position            BIGINT,
    event_type          VARCHAR(500)             NOT NULL,
    serialized_payload  BYTEA                    NOT NULL,
    serialized_metadata BYTEA                    NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_at             TIMESTAMP WITH TIME ZONE,
    attempts            INTEGER                  NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    last_error          TEXT,
    UNIQUE (outbox, message_id),
    CHECK ((subject_id IS NULL) = (position IS NULL))
);

CREATE INDEX IF NOT EXISTS smd_outbox_unsent ON smd_outbox (outbox, id) WHERE sent_at IS NULL;
CREATE INDEX IF NOT EXISTS smd_outbox_unsent_subject ON smd_outbox (outbox, subject_id, position) WHERE sent_at IS NULL;

CREATE TABLE IF NOT EXISTS smd_inbox
(
    id                  BIGSERIAL PRIMARY KEY,
    inbox               VARCHAR(255)             NOT NULL,
    message_id          UUID                     NOT NULL,
    origin              VARCHAR(255),
    subject_id          VARCHAR(500),
    position            BIGINT,
    event_type          VARCHAR(500)             NOT NULL,
    serialized_payload  BYTEA                    NOT NULL,
    serialized_metadata BYTEA                    NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (inbox, message_id),
    UNIQUE (inbox, origin, subject_id, position),
    CHECK ((subject_id IS NULL) = (position IS NULL) AND (origin IS NULL) = (position IS NULL))
);

CREATE TABLE IF NOT EXISTS smd_inbox_group
(
    inbox            VARCHAR(255)             NOT NULL,
    processing_group VARCHAR(255)             NOT NULL,
    registered_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (inbox, processing_group)
);

CREATE TABLE IF NOT EXISTS smd_inbox_cursor
(
    inbox            VARCHAR(255) NOT NULL,
    processing_group VARCHAR(255) NOT NULL,
    origin           VARCHAR(255) NOT NULL,
    subject_id       VARCHAR(500) NOT NULL,
    next_position    BIGINT       NOT NULL,
    PRIMARY KEY (inbox, processing_group, origin, subject_id)
);

CREATE TABLE IF NOT EXISTS smd_inbox_pending
(
    inbox            VARCHAR(255)             NOT NULL,
    processing_group VARCHAR(255)             NOT NULL,
    id               BIGINT                   NOT NULL REFERENCES smd_inbox (id),
    origin           VARCHAR(255),
    subject_id       VARCHAR(500),
    position         BIGINT,
    attempts         INTEGER                  NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    last_error       TEXT,
    PRIMARY KEY (inbox, processing_group, id)
);

CREATE INDEX IF NOT EXISTS smd_inbox_pending_subject ON smd_inbox_pending (inbox, processing_group, origin, subject_id, position);
