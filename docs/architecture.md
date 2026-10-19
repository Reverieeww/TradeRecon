# Architecture
Browser -> Controller -> Service -> pure domain/rules + JDBC repository -> PostgreSQL.
One application and one database. Java 21, Spring Boot 3.5.16, Spring JDBC, Flyway and server-rendered Thymeleaf.
Current packages: domain (immutable values), ingestion (field validation), reconciliation (interface only), security (local session login), web (status page), ai (interface only).
Planned packages: cases (review workflow), persistence (repositories; integration test exists), jobs (single worker and leases).
Use Clock injected as a bean, explicit as_of in pure-rule input. No implicit current time in matching.
Use defensive copies at contract boundaries. Spring/SQL/HTTP should not enter the pure engine.
Day 2: import in one transaction; diagnostics in a separate transaction after rollback.
Day 3: freeze explicit batch IDs, as_of and rules version. Day 4: repeatable-read snapshot creation, worker ownership token, atomic publication, optimistic review version.
The day-1 interface is preliminary: typed evidence/reason DTOs may evolve additively when day-3 rules are implemented. There is no promise of zero future refactoring.
AI sees read-only evidence and cannot change rules, records or case state. HTTP adapter is reserved; provider/account/model remain unconfigured. OFF by default.

