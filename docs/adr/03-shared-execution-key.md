# ADR 03 — Shared execution identity
Problem: join two reports of the same execution, not orders or deliveries.
Options: fuzzy matching; shared dataset/date/execution_id.
Decision: exact shared key; event ID and revision serve different identity/version purposes.
Cost: cannot reconcile feeds without a shared identifier or detect records absent from both.
Verification: independent partial fills, duplicate identity, semantic duplicates and conflict tests.

