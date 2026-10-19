# Local operation
Use README commands. Health: GET http://127.0.0.1:8080/actuator/health.
Local PostgreSQL port 55432; application 8080. Databases traderecon (demo) and traderecon_test (verification).
Secrets are generated in .local/settings.json. Do not share or commit it. Bootstrap does not replace existing settings/data.
If startup fails: check port conflict, .local/postgres.log, database credentials, then Flyway error. Never delete data as the first recovery step.
Migration scripts are immutable after use; future changes require V2+.
Native mode .tools contains portable binaries; on a new computer use Docker instructions or prepare Java 21/PostgreSQL 17.6 separately.
Tests use a dedicated database. Do not point TEST_DB_URL at demo/production databases.
Application restart leaves DB data in place. Stopping PostgreSQL also preserves .local/pgdata.
Recovery scenarios were implemented on Day 4; see day4-recovery.md. Retry only failed/expired reconciliation runs. A healthy lease cannot be replaced, and a new run is needed to include later imports.
AI errors: inspect the analysis status and validation_errors, then use the template. A queued job uses its recorded mode/model; another mode's worker will not consume it. Interrupted AI calls expire after 30 seconds and are not automatically rebilled; a new explicit request is required. Do not clear reservation ledgers to bypass a budget. Configure secrets only in the local terminal or ignored .env, never browser fields.
Final verification: scripts/verify-clean-native.ps1 creates and stops an isolated cluster on 55433, preserving the demo on 55432. Retained clean-test data is diagnostic evidence, not user data to delete on restart.

