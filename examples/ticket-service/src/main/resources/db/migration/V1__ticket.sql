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
