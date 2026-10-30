CREATE TABLE IF NOT EXISTS smd_dispatcher
(
    dispatcher    VARCHAR(255)             NOT NULL PRIMARY KEY,
    lane_count    INTEGER                  NOT NULL,
    registered_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE IF NOT EXISTS smd_dispatcher_subscriber
(
    dispatcher    VARCHAR(255)             NOT NULL,
    subscriber    VARCHAR(255)             NOT NULL,
    registered_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (dispatcher, subscriber)
);

CREATE TABLE IF NOT EXISTS smd_dispatcher_lane
(
    dispatcher VARCHAR(255) NOT NULL,
    subscriber VARCHAR(255) NOT NULL,
    lane       INTEGER      NOT NULL,
    PRIMARY KEY (dispatcher, subscriber, lane)
);

CREATE TABLE IF NOT EXISTS smd_dispatcher_queue
(
    id                  BIGSERIAL PRIMARY KEY,
    dispatcher          VARCHAR(255)             NOT NULL,
    subscriber          VARCHAR(255)             NOT NULL,
    lane                INTEGER                  NOT NULL,
    message_id          UUID                     NOT NULL,
    subject_id          VARCHAR(500),
    event_type          VARCHAR(500)             NOT NULL,
    serialized_payload  BYTEA                    NOT NULL,
    serialized_metadata BYTEA                    NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    attempts            INTEGER                  NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    last_error          TEXT,
    UNIQUE (dispatcher, subscriber, message_id)
);

CREATE INDEX IF NOT EXISTS smd_dispatcher_queue_lane ON smd_dispatcher_queue (dispatcher, subscriber, lane, id);
