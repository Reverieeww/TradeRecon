# Day 5 verified status — 2026-10-18

Implemented: four investigation pages; dataset/date/state filters; 50-row paging (query limit capped at 200); side-by-side business fields; frozen version/receipt timeline; paginated audit; role-aware actions; actionable HTML conflicts; all-result CSV export with formula escaping; deterministic evidence-linked templates and approved demo runbooks.

Verification: Java 21 / native PostgreSQL 17.6, Maven verify: **180 tests, 0 failures/errors/skips** (116 unit/security/fixture tests + 64 database integration tests). See evidence/day5-verify-native.log. Includes 205-case paging/export coverage, XSS escaping, stale evidence HTML 409, role restrictions, file limits, context limits, and all 70 evaluation expectations.
Live HTTP smoke also passed against the restarted desktop app: three roles, imports, idempotent background runs, claim/escalate/supervisor closure, corrected-data resolution, all four pages, template investigation, CSV download and readable invalid-filter errors. See evidence/day5-http-smoke.txt and scripts/check-day5-http.ps1.
No Docker or CI execution is claimed. Browser visual inspection remains pending and is not included in this automated test count.

Migration V5 only adds listing indexes; earlier migrations and PLAN.md are unchanged.
evidence/day5-case-query-plan.txt records EXPLAIN ANALYZE on 205 synthetic cases: index scan on case_dataset_filter and top-N sort; measured execution 0.393 ms in this run. This is one small local query, not an end-to-end SLA or proven percentage improvement.

Frozen fixtures: 60 cases across six groups plus 10 challenges; two separate development cases. Checksummed, authored labels; human review PENDING. Evaluation and B-mode timing instructions are in docs/evaluation.md and docs/day5-measurement.md.
Remaining: independent human label review, user study/timing measurements, visual review on target browser, Day 6 model integration and evaluation. AI OFF; no fabricated savings or model accuracy.

