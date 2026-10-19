# Day 2 implemented API
All routes require a login session except the health and login routes. The demo accounts can read both sources.
ANALYST and SUPERVISOR may upload; VIEWER cannot. CSRF remains enabled. POST /login with a CSRF token first; GET /api/csrf returns the token/header name for subsequent API writes.

POST /api/imports (multipart):
dataset (UUID), source (INTERNAL or BROKER), business_date (YYYY-MM-DD), feed_complete (boolean), file (*.csv).
201: new ACCEPTED batch; 200: same raw file and same metadata, returning original batch and counts.
Response: {batch: {id,datasetId,source,businessDate,feedComplete,status,counts,error,acceptedAt}, repeatedFile}.
Counts: total, inserted (new canonical events), duplicates (rows mapped to existing events), rejected.
On repeated file, these are the ORIGINAL batch counts, not new inserts from the repeated request.

GET /api/imports?dataset=UUID&page=0&size=50 : batches, newest first.
GET /api/imports/{id}?page=0&size=50 : batch plus paginated raw row provenance.
GET /api/executions/{executionId}?dataset=UUID&source=INTERNAL : version history, firstReceivedAt=min(accepted event receipt times), projection ACTIVE/CANCELLED/DATA_BLOCKED.
Price/quantity are JSON strings. Projection is not cross-source reconciliation.
GET /imports : simple diagnostic upload page using the fixed Day 2 demo dataset.
The page was brought forward from day 5 to support learning; full review UI remains day 5.

## Errors
JSON fields: code, message, fieldErrors [{row,field,message}], traceId, batchId (when saved).
Row is the 1-based logical DATA record number, excluding header; a quoted multi-line record is one record.
- 400 MALFORMED_CSV: invalid UTF-8, header, quote structure or number of columns.
- 400 INVALID_FILE_TYPE: filename is not .csv; 400 INVALID_REQUEST: request parameters missing/invalid.
- 400 INVALID_PAGE: negative page or size outside 1..200.
- 401 UNAUTHENTICATED; 403 FORBIDDEN: role or CSRF failure.
- 404 NOT_FOUND: nonexistent dataset, execution or batch.
- 409 IDENTITY_CONFLICT: reused event ID with changed canonical payload, incompatible execution revision, or changed business date.
- 409 BATCH_METADATA_CONFLICT: same raw SHA but different business_date/feed_complete.
- 413 FILE_TOO_LARGE (>10 MiB) / TOO_MANY_ROWS (>20000).
- 422 VALIDATION_ERROR: field, date, account or instrument validation failed.

Example:
{"code":"VALIDATION_ERROR","message":"VALIDATION_ERROR","fieldErrors":[{"row":1,"field":"price","message":"Positive decimal; max 14 integer and 6 fractional digits"}],"traceId":"generated","batchId":"saved-rejected-batch-uuid"}

Within the file size limit, rejected files are retained; for parsed records import_row is REJECTED with no event_ref.
Malformed files retain raw bytes and error_summary; parsing may not produce row associations.
Oversized HTTP/file requests are rejected before raw storage to preserve the storage cap.
Rejected file retries return rejection; they do not change accepted data.
Raw files are not publicly downloadable; authenticated batch details include paginated raw fields.

## Input/identity details
Exact 13-column header/order required; optional UTF-8 BOM accepted. CSV parsing uses Commons CSV 1.14.1.
Decimal syntax: 1..14 integer digits, optional 1..6 fractional digits; positive, no exponent syntax.
Timestamp requires explicit UTC or offset. Date is request business date, never inferred from local clock.
Same-ID canonical payload includes occurred_at but excludes note; notes remain raw provenance.
Semantic revision equality excludes occurred_at and note, and includes all business fields and event_type.
Each semantic duplicate event ID is remembered in event_identity.
Source is supplied through request; all three demo users have read access to both sources. No tenant entitlement feature is claimed.
