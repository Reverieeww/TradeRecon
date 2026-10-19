# Day 2 additions to the data dictionary
Authority for V1: PLAN.md section 05 and src/main/resources/db/migration/V1__schema.sql. V1 was not edited.
V2__import_identity.sql:
- event_identity(dataset_id, source, event_id): primary key; maps every observed message ID to canonical trade_event.id. payload_hash verifies its canonical payload.
- event_identity.event_ref: foreign key to immutable canonical event. Multiple IDs can refer to one event.
- import_batch.created_at: server timestamp for stable batch listing; accepted_at remains NULL for rejected batches.
- fixed demo dataset UUID 00000000-0000-0000-0000-000000000001.

trade_event.received_at is the server receipt time for a newly accepted canonical revision.
firstReceivedAt for an execution is MIN(received_at); retries and semantic duplicate deliveries do not reset it.
import_row.row_no is 1-based data record, not physical text line; quoted newlines remain one record.
import_row.raw_payload is the decoded original field map. import_batch.raw_csv preserves exact bytes.
row_counts records total/inserted/duplicates/rejected, with inserted counting canonical events.
error_summary stores code/httpStatus/fieldErrors. Rejected batches never declare accepted_at.
Runtime can SELECT/INSERT event_identity but cannot UPDATE/DELETE; event and audit protections remain unchanged.

Frozen-input note for day 3: select events via import_row associations from explicit accepted run_batch IDs, including DUPLICATE row references. Do not select only first_batch_id or latest global tables.
