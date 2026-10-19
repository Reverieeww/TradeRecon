# ADR 05 — Database invariants and version checks
Problem: duplicates, competing reviewers and revived workers.
Options: in-memory locks; database unique constraints, row locks, version/evidence proof and owner tokens.
Decision: PostgreSQL is the source of concurrency truth; idempotency is scoped to actor/endpoint/key.
Cost: contention, explicit 409 reload paths and bounded transactions.
Verification: simultaneous imports/reviews, stale proof, expired lease and fenced old-worker tests. No end-to-end exactly-once promise.

