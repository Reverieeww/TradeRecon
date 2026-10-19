# Day 4 status

Authority: original PLAN.md sections6,8,11,16. Original plan and V1–V3 migrations are unchanged; V4 adds run creation time and queue/heartbeat indexes.

Implemented: persistent asynchronous queue; atomic idempotent snapshot creation; single-worker admission;10-second heartbeat/60-second lease expiry; owner-token fencing; frozen-input retry; bounded database waits; manual claim/escalate/supervisor close; version/result/hash checks; atomic manual audit; demo user identity persistence; diagnostic forms and common request/503 error handling.

Automated verification: **96 tests** (35 unit/security +61 real PostgreSQL integration), including18 day-4 recovery/concurrency tests. Four test cases forcibly terminate a separate test process at import/calculation/publication/after-commit boundaries. See recovery report and final Maven log for actual execution evidence. No tests are skipped to replace PostgreSQL with H2.

Real HTTP verification also passed on 2026-10-18 with separate analyst/supervisor/viewer sessions:202 submission, same-key replay, conflicting-body409, background completion, viewer403, claim, stale-version409, escalation, analyst-close403, supervisor closure, unchanged-evidence rerun, correction-driven resolution, retained old snapshot and rendered case audit. Evidence: `evidence/day4-http-smoke.txt`. The Day3 flow was rerun successfully against the asynchronous API; its new output is retained separately in `evidence/day4-day3-regression.txt`.

Remaining: full day-5 investigation UI/export, AI integration/evaluation, user administration, external deployment/security hardening, multi-worker throughput and container/CI validation. AI remains OFF. The native PostgreSQL environment is tested; Docker/Testcontainers path is not executed on this computer.

No new throughput improvement or business-efficiency percentage is claimed. The existing72.9% Day2 benchmark remains specifically local median ingestion time, not reconciliation/recovery latency.
