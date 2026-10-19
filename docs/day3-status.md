# Day 3 implementation and limits

Authority: PLAN.md section 15. Original plan and old database migrations are unchanged.

Implemented: frozen accepted-batch IDs under REPEATABLE READ; server as_of; rules version; deterministic version-aware comparison; four main statuses; 10-minute boundary; all seven business comparisons; cancellation handling; structured evidence/reasons and SHA-256 business evidence hash; atomic result/case/audit publication; stable cases and automatic resolution/reopening; paginated API and diagnostic pages.

Validation passed on 2026-10-18 against Java 21 and native PostgreSQL 17.6 in the separate traderecon_test database: **78 tests, zero failures/errors/skips** (35 unit/security + 43 integration, including 20 golden cases). See `evidence/day3-verify-native.log` for the actual final Maven result. No Docker is installed here; Testcontainers, Compose and hosted CI remain unverified. No new throughput/latency improvement is claimed for Day 3.

Tests include the original 20 golden scenarios through CSV import and PostgreSQL (not just mocked inputs), 9:59/10:00/10:01, 100 seeded reorder/duplicate permutations, full difference lists, cancellation, frozen inputs and rejected-batch diagnostics, same-evidence reruns, closed-case reopening on changed evidence, data resolution, atomic rollback with injected publication failure, active-run exclusion, role/CSRF checks and rendered HTML.

Real HTTP smoke also passed on 2026-10-18: authenticated multipart import → four statuses → identical rerun retains two cases → correction/gap repair → four matches and two RESOLVED_BY_DATA cases → original results unchanged → run/case HTML renders. Evidence: `evidence/day3-http-smoke.txt`. Reproduce with `pwsh -ExecutionPolicy Bypass -File scripts/check-day3-http.ps1` while the local app is running; it creates a new isolated synthetic dataset and leaves existing demo data intact.

Intentional scope boundaries:

- Day 3 runs synchronously and returns 201. Worker queue, owner_token enforcement, heartbeat, crash recovery, retry and HTTP idempotency are Day 4, not implemented. An interrupted RUNNING run can block further runs; do not claim automatic recovery.
- Automatic case updates exist. Manual reviewer endpoints/optimistic locking do not; tests prepare a closed state directly in the dedicated test database to verify future compatibility.
- Diagnostic pages were brought forward to make the feature testable by the learner; full reviewer UI/export remain Day 5.
- AI remains OFF. No real customer/trade data, external messages or paid services.
- Evidence hashing uses fixed-order canonical business fields and rules, excludes delivery metadata/run ID/as_of. Rule-version changes must accompany changes to this canonical representation. Raw-file provenance is separately retained in batches.
- Inputs and results currently fit in memory; database-scale streaming and query-plan tuning are not claimed. No independent authority can detect trades missing from both feeds.
- Original rejected input makes later runs PROVISIONAL until a future remediation policy is implemented; a successful import alone does not silently clear earlier rejected batches.
