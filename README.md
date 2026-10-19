# TradeRecon
A Java application for reconciling two **synthetic execution feeds**, with traceable imports, deterministic matching and automatic case history.

For a June business-date demonstration, use [the synthetic June dataset](data/june-demo/README.md).
For privacy checks before publishing, see [the publication checklist](docs/publication-privacy.md).

## Status: Day 6–7 release candidate
Implemented: transactional ingestion, frozen snapshots, deterministic reconciliation, automatic cases, durable queue and recovery, concurrent review safeguards, and append-only audit. Day 5 adds filtered/paginated import, run and case pages, field comparison and version timelines, full CSV export, and evidence-linked deterministic investigation templates.
Day 6 adds OFF/MOCK/LIVE investigation, a real HTTP adapter, bounded persistent queue, output/reference validation, evidence cache, stale-output handling, conservative budget reservations and template fallback. AI defaults to OFF; no live calls have been made.
Start with [AI setup](docs/ai-setup.md), [release acceptance](docs/day7-release.md) and [scale measurements](docs/day7-performance.md). The frozen 60+10 cases have authored labels with human review PENDING. No live AI quality or investigation-time improvement is claimed. Day 1–5 documents describe historical milestones.
On this Windows machine, all three synthetic scales completed one warm-up and ten measured runs. At 100,000 total two-feed events (50,000 keys), median ingestion was 9.90 seconds and reconciliation 12.03 seconds. This is service-level timing, not HTTP latency; see the report for ranges and limits.
See the [API contracts](docs/api.md), [recovery report](docs/day4-recovery.md) and [ingestion performance experiment](docs/day2-performance.md). Earlier day documents are historical snapshots.
Local benchmark: median service ingestion time for 10,000 synthetic events decreased from 3.83s to 1.04s using batched JDBC writes (72.9%, 10 measured runs per mode). This excludes HTTP and reconciliation. Both complete experiment rounds are retained.

This is **not** a limit order book or matching engine. Personal planning and learning material is not included in the current published tree. Historical reports may refer to the original private plan; public acceptance criteria and outstanding checks are recorded in the [release checklist](docs/day7-release.md). Existing Git history is unchanged.

## Quick start on this Windows computer
Open PowerShell in this project folder:
```powershell
.\scripts\start-local.ps1
```
Then open http://127.0.0.1:8080. Usernames: viewer, analyst, supervisor.
If the background demo from the first-day setup is already running, use it directly. Before starting another instance, run `.\scripts\stop-local-app.ps1`.
The generated local password is the `demoPassword` field in `.local/settings.json` (ignored by Git). All three accounts share that demo-only password. Spring Security authenticates the environment-configured demo accounts; their BCrypt hashes and stable identity/role records are synchronized to app_user at startup for audit and assignment. Backend role checks are implemented; arbitrary user provisioning/password management is not.
If PowerShell blocks scripts, use a session-scoped invocation: `pwsh -ExecutionPolicy Bypass -File .\scripts\start-local.ps1`. No machine policy changes are required.

For tests, in a second terminal:
```powershell
.\scripts\verify-local.ps1
```
This loads project-local Java 21 and tests **real PostgreSQL 17.6** on 127.0.0.1:55432. It does not change global PATH. Test and demo databases are separate. Keep .tools and .local private and out of Git.

Stop the foreground app with Ctrl+C. To stop the project database:
```powershell
& .\.tools\postgres\pgsql\bin\pg_ctl.exe -D .\.local\pgdata -m fast -w stop
```
Data remains in .local/pgdata. Starting again reuses it. Do not delete that directory to restart.

## Clean-machine path (Windows or Linux, Docker required)
Install JDK 21 and a working Docker Engine/Desktop with Linux containers.
Copy .env.example to .env; set three distinct random passwords (DEMO_PASSWORD >= 12 characters).
```sh
docker compose up --build -d
./mvnw -B -ntp verify
python scripts/generate_data.py --check
```
Windows uses `.\mvnw.cmd`. The default verification starts a disposable PostgreSQL 17.6 Testcontainer, runs migrations on an empty database and fails if Docker is unavailable.
The app is at http://127.0.0.1:8080. Database ports are not published by Compose. `docker compose stop` preserves data.
Container/CI execution is **not yet verified on this computer**, which has no Docker. Native PostgreSQL results do not claim container reproducibility.

## Read next
- [Progress and evidence](docs/day1-status.md): actual checks and remaining gates.
- [Domain](docs/domain.md), [architecture](docs/architecture.md), [API](docs/api.md), [decisions](docs/decisions.md).
- [Golden manifest](data/demo/manifest.json): 20 independent expected answers, now exercised through real imports and reconciliation in ReconciliationIT.
- [Runbook](docs/runbook.md), [versions](docs/versions.md).

No live model provider or payment is configured. AI is OFF. No real financial/customer data is used.

