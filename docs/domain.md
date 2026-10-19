# Domain contract — day 1
Authority: PLAN.md sections 02–06. Rule version proposed: v1.

An order requests a transaction. An execution is a fill. An event reports a new execution, correction or cancellation. A 100-share order with one 50-share fill is not automatically an error; compare that execution with its counterpart.

ExecutionKey = (datasetId UUID, tradeDate LocalDate, executionId case-sensitive 1–64 ASCII letters/digits/_/-). Across sources execution_id is assumed shared. No fuzzy matching.
Source is INTERNAL or BROKER. Side is BUY or SELL. Currency is USD or GBP; no FX conversion.
Quantity and price are positive BigDecimal values fitting NUMERIC(20,6). Reject precision > 6 and values >= 10^14 before database insertion; PostgreSQL NUMERIC alone can round excess fractional precision. MoneyFields canonicalises to scale 6 so record equality is numeric. Build decimals from strings, never double.
Event ID is source-local within a dataset; revision is positive. Revision 1 is NEW; later revisions are CORRECT or CANCEL. An isolated revision 3 can pass field validation but is DATA_BLOCKED until the full valid chain exists. Day 2 must validate cancellation fields and no resurrection.
occurred_at is an Instant after parsing an explicit offset. CSV parsing/offset enforcement is day 2; first received time is separately stored by the server.
Reference accounts ACC01/ACC02 and instruments STOCK01/STOCK02 are seeded by migration.
Note is <=500 Unicode code points and untrusted. It never changes matching rules.

CSV header:
event_id,execution_id,revision,event_type,account_id,instrument_id,side,currency,quantity,price,trade_date,occurred_at,note
Use a CSV library (day 2), not split(','). Source, business_date and feed_complete are request metadata.
Reject files >10 MiB or >20,000 rows. Any invalid/conflicting row rejects the whole batch.

Four primary result statuses: DATA_BLOCKED, WAITING, BREAK, MATCHED. MATCHED_CANCELLED is a reason under MATCHED, not a fifth primary status.
WAITING ends at >=10 minutes from first accepted receipt, or when both sources declare complete.
Compare account/instrument/side/currency/quantity/price/cancelled and report all differences.
Reviewer state is independent: manually closing a case cannot turn BREAK into MATCHED.

Schema V1 reserves all planned tables; repositories/workflows are future work.
Uniqueness protects dataset/source/event_id and dataset/source/execution_id/revision.
Runtime role may append event and audit rows but cannot UPDATE/DELETE them.
Foreign keys prevent references to nonexistent records; unique constraints prevent duplicate identities. Neither substitutes for version-chain validation.

