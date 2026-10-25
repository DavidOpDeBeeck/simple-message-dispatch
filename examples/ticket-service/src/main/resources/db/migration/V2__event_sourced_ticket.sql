ALTER TABLE smd_event_store
    ADD COLUMN aggregate_type    VARCHAR(255),
    ADD COLUMN aggregate_version BIGINT,
    ADD CONSTRAINT smd_event_store_aggregate_revision_check
        CHECK ((aggregate_type IS NULL AND aggregate_version IS NULL)
            OR (aggregate_type IS NOT NULL AND aggregate_version IS NOT NULL AND subject_id IS NOT NULL));

CREATE UNIQUE INDEX smd_event_store_aggregate_stream
    ON smd_event_store (aggregate_type, subject_id, aggregate_version)
    WHERE aggregate_type IS NOT NULL;

DROP TABLE ticket;
