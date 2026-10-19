# ADR 02 — Exact decimals
Problem: quantities and prices require reproducible comparison.
Options: floating point with epsilon; BigDecimal with NUMERIC(20,6).
Decision: exact decimal strings; reject excess precision and compare numerically.
Cost: explicit parsing/normalization, no implicit currency conversion.
Verification: precision/range tests and equal-value/different-scale tests; never construct monetary BigDecimal from double.

