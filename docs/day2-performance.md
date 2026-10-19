# Day 2 ingestion benchmark — measured 2026-10-18
## Observed result
| Input events | Single-row median | JDBC batch median | Median reduction |
|---|---:|---:|---:|
| 1,000 | 417.277 ms | 104.653 ms | 74.92% |
| 10,000 | 3,830.803 ms | 1,039.247 ms | 72.87% |

The table uses the final-code rerun. The first complete experiment is also retained in evidence/day2-benchmark-initial.json and its log: 10,000 rows measured 3,807.509 -> 938.400 ms (75.35%). We report the final run rather than select the fastest round. Local background activity and accumulated database history differ between rounds; this is not a claim that the login fix changed ingestion speed.

This compares the SAME ImportService with its repository write strategy switched: JDBC individual updates versus batchUpdate in chunks of 500. Driver reWriteBatchedInserts=true in both runs; only batched calls benefit.
Both modes parse CSV, validate, check identities, preserve raw CSV/row provenance and commit one transaction. Database constraints and source-level locking stay enabled.
The timing covers an in-process service call, not HTTP upload, browser latency or reconciliation. Dataset setup and verification queries are outside the measured interval.

## Reproduction
On this machine, open PowerShell in the project:
```powershell
. .\scripts\local-env.ps1
.\mvnw.cmd -B -ntp verify "-Dit.test=ImportBenchmark"
```
Or run scripts/benchmark-local.ps1 (use pwsh -ExecutionPolicy Bypass -File if scripts are blocked).
It writes evidence/day2-benchmark.json and adds isolated BENCH datasets in traderecon_test. Existing datasets are not deleted. This optional benchmark is not run by ordinary verify or CI.

## Method and evidence
One warm-up per mode/size, then ten measured runs per mode/size, alternating order.
1,000-row and 10,000-row UTF-8 CSV; deterministic sequential IDs, no RNG, zero duplicate fraction. Each run uses a fresh dataset.
Each run verifies event count, identity count and provenance row count; normalized business digest must match across modes. Raw samples include dataset ID and input SHA.
Whole-file duplicate/concurrent correctness is tested separately in ImportIT, not counted as a performance improvement.

Hardware/software: Windows 11 10.0; AMD64 Family 26 Model 68 Stepping 0; 16 logical processors; Temurin 21.0.12.1; native PostgreSQL 17.6; same local storage/DB instance. JVM memory and server build are recorded in the JSON.
10,000-row ranges in final run: single 3,732.341–4,508.779 ms; batch 949.404–1,422.421 ms (raw JSON is authoritative).
No p99, production load, network concurrency, CPU/peak-memory improvement or AI benefit is claimed.
Small sample, local machine and warmed process limit generalisation. Source lock serialises imports for the same dataset/source. History/lookups may cost more on larger existing datasets.
Formula: (single median - batch median) / single median * 100.

## Resume wording supported by this experiment
Optimised transactional CSV ingestion using JDBC batch writes, reducing median import time for 10,000 synthetic trade events from 3.83s to 1.04s (72.9%) in a local benchmark across 10 measured runs per mode.

Do not describe this as total system latency, matching accuracy, real trading throughput, or a user-efficiency improvement.
