ALTER TABLE ai_analysis ADD COLUMN case_id UUID REFERENCES exception_case;
ALTER TABLE ai_analysis ADD COLUMN input_json JSONB;
ALTER TABLE ai_analysis ADD COLUMN raw_response TEXT;
ALTER TABLE ai_analysis ADD COLUMN actual_model TEXT;
ALTER TABLE ai_analysis ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ai_analysis ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE ai_analysis ADD COLUMN started_at TIMESTAMPTZ;
ALTER TABLE ai_analysis ADD COLUMN finished_at TIMESTAMPTZ;
ALTER TABLE ai_analysis ADD COLUMN owner_token UUID;
ALTER TABLE ai_analysis ADD COLUMN reserved_usd NUMERIC(20,6) NOT NULL DEFAULT 0 CHECK(reserved_usd>=0);
ALTER TABLE ai_analysis ADD COLUMN actor TEXT;
CREATE INDEX ai_cache_lookup ON ai_analysis(case_id,evidence_hash,prompt_version,provider,model,created_at DESC);
CREATE INDEX ai_queue_lookup ON ai_analysis(status,created_at);
-- Existing runtime role already has SELECT/INSERT/UPDATE on ai_analysis.

