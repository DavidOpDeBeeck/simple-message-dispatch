-- Upgrades an event store created before event logs (outbox and inbox) to the current schema.
-- Existing rows become the 'events' log, so existing processing groups continue where they left off.

ALTER TABLE smd_event_store ADD COLUMN log VARCHAR(255) NOT NULL DEFAULT 'events';
ALTER TABLE smd_event_store DROP CONSTRAINT smd_event_store_pkey;
ALTER TABLE smd_event_store ADD PRIMARY KEY (log, message_id);
CREATE INDEX smd_event_store_log_sequence_number ON smd_event_store (log, sequence_number);

ALTER TABLE smd_token_store ADD COLUMN log VARCHAR(255) NOT NULL DEFAULT 'events';
ALTER TABLE smd_token_store DROP CONSTRAINT smd_token_store_pkey;
ALTER TABLE smd_token_store ADD PRIMARY KEY (log, processing_group);

ALTER TABLE smd_event_sequence_state ADD COLUMN log VARCHAR(255) NOT NULL DEFAULT 'events';
ALTER TABLE smd_event_sequence_state DROP CONSTRAINT smd_event_sequence_state_processing_group_subject_id_key;
ALTER TABLE smd_event_sequence_state
    ADD CONSTRAINT smd_event_sequence_state_log_processing_group_subject_id_key UNIQUE NULLS NOT DISTINCT (log, processing_group, subject_id);
