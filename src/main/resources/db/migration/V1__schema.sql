-- Domain schema. UUIDs are supplied by the application. Raw events are append-only.
CREATE TABLE dataset (
 id UUID PRIMARY KEY, name TEXT NOT NULL UNIQUE,
 kind TEXT NOT NULL CHECK (kind IN ('DEMO','EVAL','BENCH')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE account (id VARCHAR(64) PRIMARY KEY);
CREATE TABLE instrument (id VARCHAR(64) PRIMARY KEY);
INSERT INTO account VALUES ('ACC01'),('ACC02');
INSERT INTO instrument VALUES ('STOCK01'),('STOCK02');
CREATE TABLE import_batch (
 id UUID PRIMARY KEY, dataset_id UUID NOT NULL REFERENCES dataset,
 source TEXT NOT NULL CHECK (source IN ('INTERNAL','BROKER')),
 business_date DATE NOT NULL, sha256 CHAR(64) NOT NULL,
 status TEXT NOT NULL CHECK (status IN ('ACCEPTED','REJECTED')),
 feed_complete BOOLEAN NOT NULL, accepted_at TIMESTAMPTZ,
 row_counts JSONB NOT NULL DEFAULT '{}', error_summary JSONB,
 raw_csv BYTEA NOT NULL CHECK (octet_length(raw_csv) <= 10485760),
 UNIQUE(dataset_id,source,sha256), UNIQUE(id,dataset_id,source),
 CHECK ((status = 'ACCEPTED') = (accepted_at IS NOT NULL))
);
CREATE TABLE trade_event (
 id UUID PRIMARY KEY, dataset_id UUID NOT NULL REFERENCES dataset,
 source TEXT NOT NULL CHECK (source IN ('INTERNAL','BROKER')),
 event_id VARCHAR(64) NOT NULL CHECK (event_id ~ '^[A-Za-z0-9_-]{1,64}$'),
 execution_id VARCHAR(64) NOT NULL CHECK (execution_id ~ '^[A-Za-z0-9_-]{1,64}$'),
 revision INTEGER NOT NULL CHECK (revision > 0),
 event_type TEXT NOT NULL CHECK (event_type IN ('NEW','CORRECT','CANCEL')),
 account_id VARCHAR(64) NOT NULL REFERENCES account,
 instrument_id VARCHAR(64) NOT NULL REFERENCES instrument,
 side TEXT NOT NULL CHECK (side IN ('BUY','SELL')),
 currency TEXT NOT NULL CHECK (currency IN ('USD','GBP')),
 quantity NUMERIC(20,6) NOT NULL CHECK (quantity > 0),
 price NUMERIC(20,6) NOT NULL CHECK (price > 0),
 trade_date DATE NOT NULL, occurred_at TIMESTAMPTZ NOT NULL,
 note VARCHAR(500) NOT NULL DEFAULT '', payload_hash CHAR(64) NOT NULL,
 first_batch_id UUID NOT NULL, received_at TIMESTAMPTZ NOT NULL,
 FOREIGN KEY (first_batch_id,dataset_id,source) REFERENCES import_batch(id,dataset_id,source),
 UNIQUE(dataset_id,source,event_id), UNIQUE(dataset_id,source,execution_id,revision),
 CHECK ((revision = 1 AND event_type = 'NEW') OR (revision > 1 AND event_type <> 'NEW'))
);
CREATE TABLE import_row (
 batch_id UUID NOT NULL REFERENCES import_batch, row_no INTEGER NOT NULL CHECK (row_no BETWEEN 1 AND 20000),
 event_ref UUID REFERENCES trade_event, outcome TEXT NOT NULL CHECK (outcome IN ('NEW','DUPLICATE','REJECTED')),
 raw_payload JSONB NOT NULL, PRIMARY KEY(batch_id,row_no)
);
CREATE TABLE recon_run (
 id UUID PRIMARY KEY, dataset_id UUID NOT NULL REFERENCES dataset, business_date DATE NOT NULL,
 rules_version TEXT NOT NULL, as_of TIMESTAMPTZ NOT NULL, input_hash CHAR(64) NOT NULL,
 status TEXT NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
 owner_token UUID, started_at TIMESTAMPTZ, heartbeat_at TIMESTAMPTZ, finished_at TIMESTAMPTZ, error JSONB
);
CREATE UNIQUE INDEX one_active_run ON recon_run(dataset_id,business_date) WHERE status IN ('QUEUED','RUNNING');
CREATE TABLE run_batch (run_id UUID NOT NULL REFERENCES recon_run, batch_id UUID NOT NULL REFERENCES import_batch, PRIMARY KEY(run_id,batch_id));
CREATE TABLE recon_result (
 id UUID PRIMARY KEY, run_id UUID NOT NULL REFERENCES recon_run,
 execution_id VARCHAR(64) NOT NULL,
 status TEXT NOT NULL CHECK (status IN ('DATA_BLOCKED','WAITING','BREAK','MATCHED')),
 reason_codes JSONB NOT NULL, evidence_json JSONB NOT NULL, evidence_hash CHAR(64) NOT NULL,
 UNIQUE(run_id,execution_id)
);
CREATE TABLE app_user (
 id UUID PRIMARY KEY, username TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL,
 role TEXT NOT NULL CHECK (role IN ('VIEWER','ANALYST','SUPERVISOR')), enabled BOOLEAN NOT NULL DEFAULT true
);
CREATE TABLE exception_case (
 id UUID PRIMARY KEY, dataset_id UUID NOT NULL REFERENCES dataset, business_date DATE NOT NULL,
 execution_id VARCHAR(64) NOT NULL, current_result_id UUID NOT NULL REFERENCES recon_result,
 review_state TEXT NOT NULL CHECK (review_state IN ('OPEN','IN_REVIEW','ESCALATED','CLOSED_WITH_REASON','RESOLVED_BY_DATA')),
 assignee UUID REFERENCES app_user, version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(dataset_id,business_date,execution_id)
);
CREATE TABLE audit_event (
 id UUID PRIMARY KEY, case_id UUID REFERENCES exception_case, run_id UUID REFERENCES recon_run,
 actor TEXT NOT NULL, action TEXT NOT NULL, before_state JSONB, after_state JSONB,
 reason TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CHECK (case_id IS NOT NULL OR run_id IS NOT NULL)
);
CREATE TABLE ai_analysis (
 id UUID PRIMARY KEY, result_id UUID NOT NULL REFERENCES recon_result, evidence_hash CHAR(64) NOT NULL,
 prompt_version TEXT NOT NULL, provider TEXT NOT NULL, model TEXT NOT NULL,
 status TEXT NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','STALE')),
 request_id TEXT, response_json JSONB, validation_errors JSONB, latency_ms BIGINT,
 token_usage JSONB, estimated_cost NUMERIC(20,6)
);
CREATE TABLE idempotency_request (
 actor_id UUID NOT NULL REFERENCES app_user, endpoint TEXT NOT NULL, key TEXT NOT NULL,
 body_hash CHAR(64) NOT NULL, run_id UUID NOT NULL REFERENCES recon_run,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(actor_id,endpoint,key)
);
CREATE INDEX batch_lookup ON import_batch(dataset_id,business_date,status);
CREATE INDEX case_listing ON exception_case(review_state,updated_at,id);
CREATE INDEX result_listing ON recon_result(run_id,status,execution_id);
-- Runtime role is provisioned before migration, separately from the migration owner.
GRANT USAGE ON SCHEMA public TO traderecon_app;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO traderecon_app;
GRANT INSERT ON dataset,import_batch,trade_event,import_row,recon_run,run_batch,recon_result,exception_case,audit_event,ai_analysis,app_user,idempotency_request TO traderecon_app;
GRANT UPDATE ON recon_run,exception_case,ai_analysis,app_user TO traderecon_app;
