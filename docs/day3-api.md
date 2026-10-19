# Day 3 implemented API

All routes require authentication. All POSTs require CSRF and ANALYST or SUPERVISOR. Viewer reads only.

- `POST /api/reconciliation-runs`: form parameters `dataset` UUID and `business_date` ISO date. Day 3 executes synchronously and returns **201** `{id,status:"SUCCEEDED"}`. `/api/runs` is an alias. No caller-controlled as_of.
- `GET /api/reconciliation-runs?dataset=...&page=0&size=50`: history; `/api/runs` alias.
- `GET /api/reconciliation-runs/{id}?page=0&size=50`: frozen batches, as_of, rules_version, input_hash, completeSources, snapshot_diagnostics, completeness, all-page counts, total and paged results including evidence. `/api/runs/{id}` alias. Non-successful runs return no results.
- `GET /api/cases?dataset=...&page=0&size=50`: cases including resolved cases.
- `GET /api/cases/{id}?page=0&size=50`: current evidence and paginated append-only audit. Same-evidence reruns intentionally retain the existing current_result_id.
- `/runs` and `/cases/{id}`: basic diagnostic HTML pages; `/runs` uses the existing fixed demo dataset. This is not the complete day-5 UI.

Default page size 50, max 200. Invalid paging 400; missing dataset/run/case 404; another active run for dataset/date 409 RUN_ACTIVE. Existing common error envelope applies to validation errors. No request idempotency key, retry endpoint or manual review endpoint yet.

Day 4 will replace the synchronous run call with the planned 202 + leased-worker lifecycle, including Idempotency-Key. The stable route, snapshot, engine and publication boundaries are already present; Day 3 does not pretend to supply recovery guarantees.

Run completeness is COMPLETE only when both accepted sources declared feed_complete and the frozen rejected-batch list is empty. Rejections remain conservatively unresolved: there is no rejection-resolution workflow yet. A later rejected import does not retroactively change an older run's diagnostics.
