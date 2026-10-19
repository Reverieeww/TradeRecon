# API contract
Current Day 6–7 additions: POST /api/cases/{id}/ai-analysis with form resultId/evidenceHash (analyst/supervisor + CSRF), 202 queued, 200 cache or explicit template fallback, 409 stale proof, 429 queue full. GET /api/ai-analyses/{id} polls status. Invalid or stale outputs are not returned as current advice. HTML form: POST /cases/{id}/ai-analysis. See [AI setup](ai-setup.md), [Day 5 pages/export](day5-api.md), and [Day 4 run/review contract](day4-api.md).

## Historical milestone notes
Day 4 current contracts: [day4-api.md](day4-api.md). Asynchronous 202, Idempotency-Key, retry and version-checked review are now implemented. Historical entries below describe earlier days.
Day 3 implemented runs/cases are documented in [day3-api.md](day3-api.md). Its synchronous 201 response is explicitly temporary; the 202 worker/idempotency contract below remains Day 4 work.
Day 2 update: the implemented imports/projection APIs and error examples are documented in [day2-api.md](day2-api.md). The day-1 snapshot below is historical; remaining business routes are still planned.
Day 1 implemented: GET / (authenticated foundation page), GET /login and POST /login (Spring Security + CSRF), POST /logout (CSRF), GET /actuator/health (minimal status).
No business API is implemented yet. The following are planned contracts from PLAN.md:
- POST /api/imports: multipart source, business_date, feed_complete, file; 201 accepted, 200 duplicate, 400 malformed, 413 size, 422 invalid row, 409 identity conflict.
- GET /api/imports and /api/imports/{id}: paginated batches and diagnostics.
- POST /api/reconciliation-runs: dataset, businessDate, Idempotency-Key ->202 runId; same key/different body ->409.
- GET /api/reconciliation-runs/{id}; POST /api/reconciliation-runs/{id}/retry.
- GET /api/cases and /api/cases/{id}; POST /api/cases/{id}/claim or /decisions: expectedVersion, resultId, action, reason; stale state ->409; unauthorized role ->403.
- POST /api/cases/{id}/ai-analysis ->202 or cached 200; GET /api/ai-analyses/{id}.
- GET /api/reconciliation-runs/{id}/export, with spreadsheet formula protection.

Planned error envelope example:
```json
{"code":"VALIDATION_ERROR","message":"Invalid input","fieldErrors":[{"row":2,"field":"price","message":"At most 6 decimal places"}],"traceId":"example"}
```
Other codes: MALFORMED_CSV (400), FILE_TOO_LARGE (413), IDENTITY_CONFLICT (409), STALE_EVIDENCE (409), FORBIDDEN (403).
Detailed executable examples accompany implementation on the relevant day.
Decimals are JSON strings. All mutation routes POST. Page size default 50, max 200.

