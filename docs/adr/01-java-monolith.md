# ADR 01 — Java 21, one deployment unit
Problem: demonstrate reliable ingestion and review without unnecessary operations.
Options: multiple services and messaging; one Java application and PostgreSQL.
Decision: one Spring Boot application, pure Java rule core, JDBC persistence.
Cost: a single deployment/worker bottleneck; no independent service scaling.
Verification: pure rule tests plus PostgreSQL integration/recovery tests. Scale only after a measured bottleneck.

