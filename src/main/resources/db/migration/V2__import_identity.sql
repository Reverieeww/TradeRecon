-- Keep every observed message ID, including IDs of semantic duplicate notifications.
CREATE TABLE event_identity (
 dataset_id UUID NOT NULL REFERENCES dataset,
 source TEXT NOT NULL CHECK (source IN ('INTERNAL','BROKER')),
 event_id VARCHAR(64) NOT NULL,
 event_ref UUID NOT NULL REFERENCES trade_event,
 payload_hash CHAR(64) NOT NULL,
 PRIMARY KEY(dataset_id,source,event_id)
);
INSERT INTO event_identity SELECT dataset_id,source,event_id,id,payload_hash FROM trade_event;
GRANT SELECT,INSERT ON event_identity TO traderecon_app;
ALTER TABLE import_batch ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX import_listing ON import_batch(dataset_id,created_at,id);
INSERT INTO dataset(id,name,kind) VALUES ('00000000-0000-0000-0000-000000000001','Day 2 demo','DEMO');
