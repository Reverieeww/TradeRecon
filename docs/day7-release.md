# Release candidate acceptance

Status: release candidate, NOT v1.0.0. The original PLAN.md remains the acceptance authority.
Implemented engineering scope: ingestion, immutable versions, deterministic reconciliation, queue/recovery, concurrent review, UI/export, evidence templates, optional AI adapter/queue/cache/validation/fallback, evaluation tooling, release measurements and documentation.

## Evidence and remaining gates
- Existing recovery tests include actual process interruption, not only mocked exceptions.
- Final native clean-cluster test log: evidence/day7-clean-native.log; all six migrations must apply from an empty schema.
- Frozen MOCK evaluation: evidence/ai-mock/*-summary.json and *.jsonl. This is contract coverage, never model quality.
- Three-scale performance: evidence/day7-benchmark.json, 30 measured samples plus three warm-ups. All business digests match within scale.
- Native HTTP release smoke is recorded separately after deployment.
- Docker/Ubuntu CI definition is provided, but this machine has no Docker; container execution is NOT claimed.
- LIVE model calls and semantic quality review: pending user-local key/model/prices/budget.
- 60+10 labels: independent human review pending.
- 20 paired B/C tasks: not performed; no human-time benefit claimed.
- Browser visual/manual acceptance and five-minute recording: pending where not explicitly recorded in the final status.
- Interview explanations/algorithm practice: user learning tasks, not automatically completed by writing files.

Do not tag v1.0.0 until the remaining required gates pass. A local release-candidate commit records completed engineering without implying full plan acceptance. Nothing is published or pushed automatically.

## Reproducibility
Windows native: scripts/start-local.ps1 and scripts/verify-local.ps1 use the existing local Java/PG distribution. scripts/verify-clean-native.ps1 creates a NEW cluster on 55433, tests an empty traderecon_test database, stops it and retains its data; it does not delete the existing demo database.
Linux / clean machine with Docker: configure .env, docker compose up --build -d, ./mvnw -B -ntp verify, python scripts/generate_data.py --check, python scripts/generate_eval.py --check.
Dockerfile uses a non-root app user and HTTP health check; Compose keeps PostgreSQL private and persistent. Stop with docker compose stop; do not use down -v to restart data.
JDK image tags are not digest-pinned; record resolved digests during the first verified container release. A native clean cluster is evidence of fresh migrations, not proof of container reproducibility.

