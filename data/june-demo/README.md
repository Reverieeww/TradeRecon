# Synthetic June demonstration

These fictional fixtures were prepared with ChatGPT assistance.

Create an isolated dataset, then upload 01_internal.csv as INTERNAL and 02_broker.csv
as BROKER, with business date 2026-06-18. Leave feed_complete unchecked.
Run reconciliation. Later upload 03_broker_fix.csv as BROKER and
04_internal_fill_gap.csv as INTERNAL and run again: all four keys should match.
The original input files, audit history and earlier runs remain available.

JuneFixtureTest verifies parsing, initial discrepancies and the final four matches.