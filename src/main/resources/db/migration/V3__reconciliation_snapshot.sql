-- Frozen diagnostics are independent of later imports. Original migrations stay unchanged.
ALTER TABLE recon_run ADD COLUMN snapshot_diagnostics JSONB NOT NULL DEFAULT '{}'::jsonb;
CREATE INDEX recon_run_history ON recon_run(dataset_id, business_date, as_of DESC, id);
CREATE INDEX exception_case_history ON exception_case(dataset_id, business_date, updated_at DESC, id);
CREATE INDEX audit_case_history ON audit_event(case_id, created_at, id);
CREATE INDEX import_row_event_reference ON import_row(event_ref);
