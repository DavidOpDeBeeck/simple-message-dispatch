ALTER TABLE smd_event_store RENAME COLUMN message_id TO event_id;
ALTER TABLE smd_event_store RENAME COLUMN subject_id TO aggregate_id;
ALTER TABLE smd_event_store RENAME COLUMN serialized_payload TO payload;
ALTER TABLE smd_event_store RENAME COLUMN serialized_metadata TO metadata;
ALTER TABLE smd_event_store RENAME COLUMN created_at TO occurred_at;
ALTER TABLE smd_event_store RENAME COLUMN sequence_number TO global_position;
ALTER TABLE smd_event_store
    ADD COLUMN aggregate_type    VARCHAR(255),
    ADD COLUMN aggregate_version BIGINT,
    ADD CONSTRAINT smd_event_store_aggregate_revision_check
        CHECK ((aggregate_type IS NULL AND aggregate_version IS NULL)
            OR (aggregate_type IS NOT NULL AND aggregate_version IS NOT NULL AND aggregate_id IS NOT NULL));

CREATE UNIQUE INDEX smd_event_store_aggregate_stream
    ON smd_event_store (aggregate_type, aggregate_id, aggregate_version)
    WHERE aggregate_type IS NOT NULL;

ALTER TABLE smd_token_store RENAME COLUMN last_processed_sequence_number TO last_processed_position;
ALTER TABLE smd_token_store RENAME COLUMN gap_sequence_number TO gap_position;

ALTER TABLE smd_event_sequence_state RENAME COLUMN subject_id TO aggregate_id;
ALTER TABLE smd_event_sequence_state RENAME COLUMN last_processed_sequence_number TO last_processed_position;

DROP TABLE ticket;
