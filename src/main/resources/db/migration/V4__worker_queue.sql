ALTER TABLE recon_run ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp();
CREATE INDEX recon_queue ON recon_run(status,as_of,id);
CREATE INDEX recon_heartbeat ON recon_run(heartbeat_at) WHERE status='RUNNING';
