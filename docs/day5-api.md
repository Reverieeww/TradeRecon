# Day 5 additions
GET /imports: dataset, date, source, status, page. POST /datasets: name (analyst/supervisor, CSRF); creates synthetic DEMO dataset.
GET /imports/{id}: provenance page. GET /runs: dataset/date/status/historyPage; id/page select run and result page independently.
GET /cases: dataset/date/review/status/execution/page; exact execution filter. GET /cases/{id}: page for audit, timelinePage for frozen timeline.
GET /api/cases/{id}/investigation: bounded evidence facts, deterministic TEMPLATE report, ai=OFF.
GET /api/reconciliation-runs/{id}/export (alias /api/runs/{id}/export): authenticated CSV, SUCCEEDED only; streams all rows in pages of 200. No note fields.
All write permissions, CSRF and version/evidence concurrency contracts from Day 4 remain. HTML controllers return readable errors; API errors retain structured JSON.

Decisions: keep templates separate from matching; never turn a possible cause into an established fact. Use frozen input timeline, not current live event list. Bounded evidence marks truncation, does not alter the stored evidence hash. Never render notes with unescaped HTML. CSV escaping also protects leading whitespace plus formula markers. Sort cases by DATA_BLOCKED, then account/currency/instrument mismatch, then others; this is an explicit demo ordering, not bank-approved risk scoring.

