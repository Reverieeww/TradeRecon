# Days 6–7 implementation status — 2026-10-18

## Verified
- Live HTTP OFF and MOCK flows passed after deployment, including role checks, cache reuse and hiding STALE answers after corrected data. See evidence/day7-http-OFF.txt and evidence/day7-http-MOCK.txt. The temporary MOCK process was stopped; the normal app remains OFF.
- 203 automated tests (132 unit/security/contract/fixture tests + 71 PostgreSQL integration tests), zero failures/errors/skips; clean native PostgreSQL 17.6 cluster, all six migrations from empty schema. See evidence/day7-clean-native.log.
- 70 frozen MOCK evaluations retained, all structured-output contracts valid. Independent semantic/human review pending. This is not a 100% AI accuracy claim.
- 1,000 / 10,000 / 100,000 total two-feed events: one warm-up and ten measurements each, stable business digests; raw results and limitations in docs/day7-performance.md.
- Live HTTP adapter tested against a local fake HTTP endpoint: structured request, 429/5xx retry limit and response deadline. NO external model calls or fees in this development run.
- Fault/permission tests cover OFF, absent credentials, budget denial, queue saturation, two-call cap, stale evidence, invalid output, timeout fallback, overlong context, mode isolation and backend authorization.

## Implementation
Persistent AI queue, per-case proof validation, mode/model/hash/prompt cache, bounded 40-fact/8000-character evidence including frozen event times, fixed prompt and runbook whitelist, strict output validation, optional real OpenAI adapter, no model tools, no model-written trading/review state. Manual refresh plus JSON polling endpoint show progress. Raw responses and metadata are retained in ai_analysis; invalid/stale answers are hidden.
Windows configuration prompts hide the key and keep it process-scoped. Budgets reserve conservatively for up to two attempts; UI and evaluation have separate cumulative ledgers. Unknown failed-call usage is not invented. Provider-side billing controls remain necessary.

## Still required before full-plan completion / v1.0.0
Your actual model/key/prices/budget and approved LIVE evaluation; human semantic/label review; 20 paired B/C timing tasks; container/CI execution on a Docker-capable machine; browser manual acceptance where not separately recorded; the five-minute video and your interview practice.
A candidate release is appropriate now. No LIVE benefit, completed user study, completed video or production readiness is claimed.
Computer Use inspected available windows; browser authentication must be performed by the user. Visual acceptance remains pending login, rather than being inferred from HTTP tests.

