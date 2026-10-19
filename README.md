# TradeRecon

TradeRecon compares internal trade records with broker execution reports and turns discrepancies into cases for review. It handles duplicate uploads, corrections, cancellations and missing revisions, while keeping the original records available for investigation.

The project uses Java 21, Spring Boot, PostgreSQL, Spring JDBC, Flyway and Thymeleaf. All sample data is synthetic.

## What it does

Upload a CSV from each source, run reconciliation, then inspect the differences in the case view. Each run uses a frozen set of input batches, so later uploads do not change an existing result.

- Imports validate each row and detect duplicate files and events. Corrections create new revisions instead of overwriting the original event.
- Reconciliation compares the effective records from both sources and creates cases for discrepancies or incomplete data.
- The web interface includes filters, field comparisons, revision timelines, review actions and CSV exports.
- Background jobs persist in PostgreSQL and support recovery after interruption. Version checks prevent one reviewer from silently overwriting another reviewer's changes.

An optional AI feature helps explain the evidence in a case. It has read-only access, validates output references and falls back to a fixed template when analysis fails. It is disabled by default; the rest of the application works without a model API key.

## Run locally

For a fresh checkout, use Docker Compose with Linux containers:

```sh
git clone https://github.com/Reverieeww/TradeRecon.git
cd TradeRecon
```

Copy `.env.example` to `.env` and fill in `MIGRATION_PASSWORD`, `DB_PASSWORD` and `DEMO_PASSWORD`. Use distinct random passwords; the demo password must contain at least 12 characters. Leave `AI_MODE=OFF` for the basic demo.

```sh
docker compose up --build -d
```

Open http://127.0.0.1:8080. The demo accounts are `viewer`, `analyst` and `supervisor`; they share the password set in `DEMO_PASSWORD` and have different permissions.

Use `docker compose stop` to stop the application and keep the database. The database volume stores imported data between restarts.

The [sample dataset](data/demo/manifest.json) covers 20 scenarios, including exact matches, partial fills, duplicates and missing revisions. A smaller [June business-date example](data/june-demo/README.md) is also available.

### Windows without Docker

The native scripts expect Java and PostgreSQL binaries under `.tools/`. Those binaries are local dependencies and are not included in the repository. Once that environment is configured, start the application from PowerShell:

```powershell
.\scripts\start-local.ps1
```

The script generates a demo password in `.local/settings.json` under `demoPassword`. If an instance is already running, stop it with `.\scripts\stop-local-app.ps1` before restarting. See the [runbook](docs/runbook.md) for operational details.

## Tests

With JDK 21 and Docker installed:

```sh
./mvnw -B -ntp verify
python scripts/generate_data.py --check
python scripts/generate_eval.py --check
```

On Windows, use `.\mvnw.cmd` in place of `./mvnw`. The Python commands check that the committed fixtures match their generators. Integration tests use a disposable PostgreSQL 17.6 container and apply migrations to an empty database.

For the configured native Windows environment, `.\scripts\verify-local.ps1` runs against a separate local PostgreSQL test database. Tests cover revision handling, reconciliation, duplicate imports, recovery, concurrent reviews and access control.

The recorded verification runs used native PostgreSQL on Windows. Docker and Ubuntu CI execution were still unverified in those records.

## Performance

Batching JDBC writes reduced median ingestion time for 10,000 synthetic events from 3.83 seconds to 1.04 seconds, a 72.9% reduction across ten measured runs per mode. This measures the ingestion service, excluding HTTP and reconciliation. The [experiment report](docs/day2-performance.md) describes the setup and retains both experiment rounds.

A separate scale test used 1,000, 10,000 and 100,000 total events across both feeds, with one warm-up and ten measured runs at each size. At 100,000 events (50,000 execution keys), median ingestion took 9.90 seconds and reconciliation took 12.03 seconds. These are local measurements; see the [scale report](docs/day7-performance.md) for the ranges and test conditions.

## AI setup and current limits

The AI integration supports `OFF`, `MOCK` and `LIVE` modes. Live mode requires a model, API key, pricing configuration and an explicit budget. The [setup guide](docs/ai-setup.md) explains the configuration and separate budgets for web requests and batch evaluation.

Recorded AI tests use mock responses. Live model quality and any reduction in investigation time have not been measured. The evaluation set contains 60 cases and 10 challenges; independent review of their labels is still pending.

This is a demo application with fixed accounts, rather than a full user-management system. Browser visual acceptance is also pending. The [release checklist](docs/day7-release.md) records the remaining checks.

## Further reading

- [Domain rules](docs/domain.md) and [database fields](docs/data-dictionary.md)
- [API contracts](docs/api.md)
- [Architecture decisions](docs/adr/)
- [Recovery testing](docs/day4-recovery.md)
- [Evaluation method](docs/evaluation.md)
