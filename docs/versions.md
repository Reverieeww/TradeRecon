# Version inventory — 2026-10-15
Day 2 addition (2026-10-18): Apache Commons CSV 1.14.1; V2 import identity migration. Existing Java/Boot/PostgreSQL versions retained.

Verified locally: Temurin OpenJDK 21.0.12.1+1; Maven 3.9.11; Maven Wrapper 3.3.4; Spring Boot parent 3.5.16; PostgreSQL 17.6 (Windows native binaries).
Parent-managed resolved dependencies: Flyway 11.7.2; PostgreSQL JDBC 42.7.11; JUnit Jupiter 5.12.2; Testcontainers 1.21.4; Surefire/Failsafe 3.5.6.
Spotless 2.44.3; google-java-format 1.24.0. Compose/Testcontainers pin postgres:17.6. JDK container bases track Java 21 tags; exact image digests are not yet recorded because no container runtime is available here.
Application 127.0.0.1:8080; native PostgreSQL 127.0.0.1:55432; Compose DB internal port 5432. Database names traderecon and traderecon_test.

References checked during implementation:
- https://docs.spring.io/spring-boot/3.5/system-requirements.html
- https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-parent/3.5.16/
- https://java.testcontainers.org/modules/databases/postgres/

Portable downloads: official Adoptium Temurin 21 release; Apache Maven artifact from Maven Central; EDB PostgreSQL 17.6 Windows binaries. They live in ignored .tools and are not repository dependencies.
