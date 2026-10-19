# Decisions
## ADR-010: Durable single-worker queue, fencing and concurrent review (day 4)
See [day4-api.md](day4-api.md) for queue/lease/idempotency/locking decisions and [day4-recovery.md](day4-recovery.md) for actual forced-process-termination evidence. No Redis/Kafka, no claim of end-to-end exactly-once, no production tamper-proof audit claim.

## ADR-009: Frozen inputs, atomic publication and stable cases (day 3)
See [day3-decisions.md](day3-decisions.md) for the transaction boundaries, canonical evidence format, full case transition table and explicit day-4 recovery limits.

## ADR-008: Preserve semantic duplicate message identities (day 2)
Use event_identity to remember every accepted event ID, even when two IDs refer to one business revision. Otherwise a previously ignored alias could later be reused with different content undetected. Cost: one additional insert/lookup table; integration test covers alias reuse.

## ADR-009: Source-scoped import serialisation and atomic batch writes (day 2)
Acquire a PostgreSQL transaction advisory lock for dataset/source before reading identity state and writing. Database uniqueness remains mandatory; unique conflicts roll back then retry/read (max three attempts). This limits source concurrency but simplifies deterministic first receipt and avoids races across version dates. Lock hash collisions can only reduce concurrency, not corrupt identity checks.
Use identical validation and transaction boundaries for the benchmark baseline and optimized JDBC batch strategy. Only repository write execution differs. HTTP cannot select the slower benchmark mode.
## ADR-001: One Java application
Choice: modular packages in a Spring Boot application rather than distributed services.
Reason: current workload and seven-day scope do not justify distributed transaction/operational costs.
Cost: single deployment and initial worker bottleneck. Verify correctness and measure before splitting.

## ADR-002: Exact decimal values
Choice: BigDecimal constructed from decimal text and canonical scale 6; PostgreSQL NUMERIC(20,6).
Alternative: binary floating point. Rejected because decimal equality is a business rule.
Cost: validation and more allocations. Verified by boundary and equality tests.

## ADR-003: Shared execution identity
Choice: exact matching by dataset/date/execution ID, not fuzzy identifiers.
Cost: cannot reconcile providers without a common ID; document that limit.
Verification: golden cases include multiple fills, duplicates and revisions.

## ADR-004: Freeze input (implementation day 3/4)
Choose explicit accepted batch IDs + as_of + rule version over querying current tables while calculating.
Cost: snapshot storage; benefit: reproducible replay. Snapshot isolation is not reviewer concurrency control.

## ADR-005: Database integrity and separate owners
Choose SQL unique/FK/CHECK constraints plus application validation. Migration owner differs from runtime role.
Cost: error mapping and rollback paths. Runtime cannot delete events or modify audit rows; real database test verifies permissions.
Optimistic locking and ownership tokens remain day-4 work.

## ADR-006: AI investigation only (implemented day 6)
Choose evidence-bound HTTP provider adapter with OFF/MOCK/LIVE over AI deciding matches.
Cost: schema validation and separate evaluation. No LIVE account/key/model is selected or tested yet.
See the six expanded decisions in [adr/](adr/), especially [read-only AI](adr/06-read-only-ai.md).

## ADR-007: Native PostgreSQL fallback on this machine
Docker is absent. Use portable Java and native PostgreSQL 17.6, loopback only, no global installation.
Same migration/test class can use explicit TEST_DB_URL for the dedicated local test database; absent this variable, Testcontainers is mandatory.
Native success cannot mark Docker/CI gates passed. Container validation remains required before release.

