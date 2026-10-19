# ADR 04 — Freeze accepted batch IDs
Problem: new arrivals must not change an in-flight run or its retry.
Options: query live latest data; save input membership under REPEATABLE READ.
Decision: run_batch, as_of and rules version are immutable inputs; publication is atomic.
Cost: snapshot storage, complete recalculation after interruption.
Verification: late import isolation and actual process-kill recovery tests.

