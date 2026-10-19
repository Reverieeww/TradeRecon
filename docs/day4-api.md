# Day 4 current API and architecture

## Run submission and retry

`POST /api/reconciliation-runs` (alias `/api/runs`): form fields `dataset`, `business_date`; required `Idempotency-Key` header (1–128 ASCII letters/digits/underscore/hyphen). Session + CSRF + analyst/supervisor required. Returns **202** `{id,runId,status}`. Status can already be SUCCEEDED if worker finished quickly.

Scope is `(actor_id, canonical endpoint, key)`; aliases share the canonical endpoint. Request hash covers normalized dataset/date. Same key/body returns original run; different body gives409 IDEMPOTENCY_CONFLICT. The snapshot, run_batch list and idempotency record commit in one REPEATABLE READ transaction. Concurrent unique/serialization conflicts retry in fresh transactions up to three attempts. Keys are retained; no expiry/cleanup yet.

GET list/detail routes and pagination are unchanged. Only SUCCEEDED exposes results. Frozen rejected-batch IDs/completeness stay visible, even when individual results match.

`POST /api/reconciliation-runs/{id}/retry` (alias `/api/runs/{id}/retry`): 202, same run and frozen inputs. FAILED or expired RUNNING becomes QUEUED with old token cleared. SUCCEEDED/QUEUED returns current run without duplicate publication. Healthy RUNNING gives409 RUN_ACTIVE. A failed snapshot older than another queued/running/successful run gives409 STALE_RUN to prevent regressing current cases. Submission/retry serialize their dataset/date admission with a database advisory lock; the partial unique index remains the final constraint.

## Worker and transaction decisions

- Database-backed single worker, fixed-delay queue check every1s. Short global advisory lock admits at most one healthy worker across app instances. Claim uses row locking, writes a fresh owner_token and records audit.
- Independent heartbeat thread every10s. More than60s without heartbeat makes the run recoverable. Late heartbeats cannot renew an already-expired lease.
- Calculation outside transaction from the immutable batch list. Publication locks the run and validates RUNNING + token + unexpired heartbeat before writing results/cases/audit/SUCCEEDED atomically. Failure marks FAILED only for the still-current live owner. A stale worker cannot fail or complete its replacement's run.
- SQL statement timeout20s, lock timeout5s, connection acquisition10s, socket timeout30s; publication transaction timeout30s, all below the lease window. Large inputs may fail a bounded transaction rather than hang forever; performance tuning is not claimed here.
- No failure hooks in the application artifact. Crash probe and mutable clock exist only in test sources. The normal app always uses server UTC time.
- This is recoverable at-least-once calculation with fenced atomic publication, not end-to-end exactly-once delivery.

## Review operations

`POST /api/cases/{id}/claim`: form fields `expectedVersion`, `resultId`, `evidenceHash`, `reason`.

`POST /api/cases/{id}/decisions`: same fields plus `action` = CLAIM / ESCALATE / CLOSE. Reasons must be nonblank and <=500 Unicode code points. Result/hash/version are mandatory, not a caller-provided target review state.

| Action | Current state | Actor | Result |
|---|---|---|---|
| CLAIM | OPEN | analyst/supervisor | IN_REVIEW, assignee=current actor |
| ESCALATE | IN_REVIEW | assignee or supervisor | ESCALATED |
| CLOSE | OPEN / IN_REVIEW / ESCALATED | supervisor only | CLOSED_WITH_REASON |

Viewer cannot mutate. Resolved/closed cases cannot be manually claimed again; new discrepancy evidence drives automatic reopening. Manual actions never set data status MATCHED.

The service locks the case row first, then reads the referenced immutable result. Checking evidence after taking that row lock avoids reading a result reference from before a concurrent publication. UPDATE includes expected version and current result ID; decision audit is in the same transaction. Successful decision returns `{id,reviewState,version}`. Stale version/result/hash ->409 STALE_EVIDENCE; forbidden role ->403; invalid state ->409 INVALID_TRANSITION; invalid reason/fields ->400.

Backend checks are authoritative; basic HTML forms expose actions but cannot bypass authorization. Persistent demo identities mirror the environment-configured in-memory Spring Security accounts; this is not a user-administration system.

## Errors and scope

Existing common envelope: code, message, fieldErrors, traceId. Missing idempotency header400; missing object404; data-access failures503 DATABASE_UNAVAILABLE (generic message, no credentials/SQL). Case and run pages remain diagnostic UI; refresh to observe background completion. Full investigation layout, exports and AI remain Days5–6. Rejected imports remain conservatively unresolved rather than silently ignored.
