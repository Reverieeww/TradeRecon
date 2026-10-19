# Release scale experiment — 2026-10-18

Scope: total events across BOTH feeds; 1,000 / 10,000 / 100,000 events correspond to 500 / 5,000 / 50,000 execution keys. 1% of keys have a price discrepancy, no duplicate rows. Deterministic sequential IDs, no random sampling.
Each size: one warm-up excluded, ten measured repetitions, fresh BENCH dataset per repetition. Batches are at most 10,000 rows and remain within file limits. All count/status assertions and normalized business-evidence digests passed. No failed measurement.

| Total events | Import median (range), seconds | Reconciliation median (range), seconds |
|---|---|---|
| 1,000 | 0.131 (0.102–0.223) | 0.186 (0.143–0.217) |
| 10,000 | 0.895 (0.808–1.009) | 1.171 (1.081–1.283) |
| 100,000 | 9.896 (9.345–13.044) | 12.028 (11.159–15.244) |

Import includes parse/validate/deduplicate/write/commit. Reconciliation includes input snapshot/load, rule computation and transactional publication/case handling. Excludes HTTP/upload time, input generation, setup, verification queries and AI.
Raw measurements, CPU, OS, Java/PostgreSQL versions, heap bound, base commit and source hash: evidence/day7-benchmark.json. sumOfHeapPoolPeaksBytes is the SUM of per-pool JVM peak readings, not simultaneous process peak RSS. Ten samples are insufficient for reliable P99 claims.

The application, benchmark and later fresh-cluster verification shared this workstation; background load is not controlled and may explain variation. Do not claim laboratory isolation or production SLA.
The benchmark lifecycle later failed formatting validation because other AI-source edits were in progress; all 33 warm-up/measured runs themselves completed and passed business checks. Final formatted correctness is verified separately in the clean-cluster log. Do not interpret that formatting failure as a dropped sample.

Bottleneck analysis: publication still issues per-result inserts and case lookups. This keeps a simple atomic publication boundary but adds round trips at 50,000 keys. Batched publication is a measured future optimization candidate, not implemented or claimed here. Day 2's batched-ingestion comparison is a separate valid baseline; do not mix its percentage with this end-to-end reconciliation measurement.
The retained 100,000-event dataset has 500 discrepancy cases. evidence/day7-query-plan.txt records a 50-row case-list probe: indexed dataset lookup, indexed result join and top-N sort, execution 3.613 ms. This is one diagnostic query, not a general latency guarantee; the database chose an existing unique index rather than assuming every new index is useful.
Reproduce explicitly with ./mvnw -B -ntp -Dit.test=ReleaseBenchmark verify against a dedicated test DB. It is intentionally excluded from routine CI timing gates.

