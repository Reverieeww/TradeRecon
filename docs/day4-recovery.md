# Day 4 actual recovery verification

Date: 2026-10-18. Java21, native PostgreSQL17.6, dedicated `traderecon_test`. No Docker/hosted CI execution is claimed.

`RecoveryIT.actualChildProcessKillAtFourBoundaries` starts a separate Java application on an ephemeral port with the worker disabled. `CrashWorkerProbe` is on the test classpath only. The probe emits a marker at a precise boundary; the parent terminates that exact child process, waits for its exit, then checks database state. It never kills the user's demonstration server.

| Forced termination point | Database assertion and recovery |
|---|---|
| After import writes, before import commit | Zero committed batches/events; retry imports exactly one event; repeated file does not duplicate it |
| After claim, before result publication (calculation phase) | No results/cases; after simulated lease expiry a newly constructed worker claims a new token and publishes the original frozen baseline |
| After result/case/audit writes, before outer transaction commit | No partial results or case audit survive; recovery publishes the same baseline once |
| After successful publication commit, before caller receives response | Original successful run survives; same idempotency key and retry return it without extra results/audit |

Evidence logs: `evidence/day4-process-import.log`, `day4-process-calculation.log`, `day4-process-publication.log`, `day4-process-after-commit.log`. Each ends with a PASS line after assertions. Full build: `evidence/day4-verify-native.log`.

Lease expiry is accelerated by a test-only Clock after killing the child; this is a real process/transaction-rollback check but **not a wall-clock 60-second recovery-latency benchmark**. It does not test machine power loss or PostgreSQL server crash durability.

Other verified failure cases: stale token publication/heartbeat/failure attempts cannot affect the new owner; healthy heartbeats prevent recovery; simultaneous same-key submissions create one run; two reviewers using the same version yield exactly one success; reviewer waiting while new results publish gets409; automatic publication reads a manual closure made during calculation and preserves it for identical evidence.

Database unavailability test points the worker at an unreachable local endpoint, observes connection failure and an unchanged queued job, then processes it with a healthy connection. It does not shut down the user's shared PostgreSQL server. Case/publication rollback tests use the real database, not H2.

During repeat verification, a test initially reused a fixed idempotency key across fresh datasets in the persistent test database. The service correctly returned409; test keys were changed to include the per-test dataset ID. This was a test-isolation defect, not a measured service outage or performance improvement. No existing database data was deleted to make tests pass.
