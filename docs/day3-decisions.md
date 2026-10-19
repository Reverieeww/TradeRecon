# Day 3 architecture decisions

## Snapshot and transaction boundaries

1. REPEATABLE READ transaction freezes explicit accepted batch IDs and rejected diagnostics, rule version, server UTC time (microsecond precision consistent with PostgreSQL), input hash. A partial unique index allows only one active run per dataset/date.
2. Read immutable events through run_batch/import_row, including DUPLICATE references; project and compare outside transactions.
3. Publication locks the run, inserts every result, locks and updates current cases, appends audit, then sets SUCCEEDED in the same transaction. Any failure rolls this phase back. FAILED is persisted separately. Successful re-execution is a no-op.

Snapshot isolation is defined by PostgreSQL visibility, not by largest batch ID or a query against current trade_event rows. input_hash identifies selected batch IDs + dataset/date + rules + as_of; evidence_hash identifies stable business evidence. The hashes serve different purposes.

## Automatic case transition table

| Existing case | Result | Evidence | Action |
|---|---|---|---|
| None | WAITING or MATCHED | any | no case |
| None | BREAK or DATA_BLOCKED | any | create OPEN and audit |
| Any | any unchanged state | same | no update/audit/version bump |
| OPEN / IN_REVIEW / ESCALATED | discrepancy | changed | preserve review state, update evidence and audit |
| CLOSED_WITH_REASON | discrepancy | same | remain closed |
| CLOSED_WITH_REASON | discrepancy | changed | reopen OPEN and audit |
| RESOLVED_BY_DATA | discrepancy | any | reopen OPEN and audit |
| Existing | MATCHED | changed or not yet resolved | RESOLVED_BY_DATA and audit |
| Existing | WAITING | changed | preserve review state, update evidence; WAITING is not proof of resolution |

Human state is read under row lock during publication, not taken from a stale calculation-time copy. Day-4 review endpoints must use the same transaction discipline plus expected_version/result_id checks.

## Limits and canonicalization

Java HashMap grouping/connection averages O(n+m), per-group revision sorting adds sum(k log k), and deterministic output sorting adds O(u log u), where u is distinct execution keys. Storage in memory is O(n+m).

Evidence includes both sides' distinct sorted business revision representations, current projected fields, status and reason codes. Canonical fields contain only validated IDs, enums, integer revisions and fixed-scale decimals, serialized in explicit insertion order. Arbitrary note text is excluded, so delivery metadata cannot disturb hash stability. This canonical format belongs to recon-v1; changes require a new rules version and retained old evaluator for old snapshots.

Only recon-v1 currently exists; unknown rule versions fail instead of being evaluated with new rules. There is no guarantee of process-crash recovery until Day 4 implements leases/fencing and retry.
