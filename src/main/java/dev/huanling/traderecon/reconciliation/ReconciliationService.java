package dev.huanling.traderecon.reconciliation;

import dev.huanling.traderecon.jobs.LeaseService;
import dev.huanling.traderecon.jobs.LeaseService.Lease;
import dev.huanling.traderecon.persistence.ImportRepository;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReconciliationService {
  private final SnapshotService snapshots;
  private final CaseService cases;
  private final JdbcTemplate jdbc;
  private final ImportRepository json;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final LeaseService leases;
  private final ReconciliationEngine engine = new DefaultReconciliationEngine();

  public ReconciliationService(
      SnapshotService snapshots,
      CaseService cases,
      JdbcTemplate jdbc,
      ImportRepository json,
      Clock clock,
      PlatformTransactionManager manager) {
    this.snapshots = snapshots;
    this.cases = cases;
    this.jdbc = jdbc;
    this.json = json;
    this.clock = clock;
    tx = new TransactionTemplate(manager);
    tx.setTimeout(30);
    leases = new LeaseService(jdbc, json, clock, manager);
  }

  /** Internal synchronous helper retained for deterministic tests; HTTP submits to the queue. */
  public UUID run(UUID dataset, LocalDate date) {
    UUID id = snapshots.create(dataset, date);
    execute(id);
    return id;
  }

  public void execute(UUID id) {
    if ("SUCCEEDED"
        .equals(jdbc.queryForObject("SELECT status FROM recon_run WHERE id=?", String.class, id)))
      return;
    execute(leases.claim(id));
  }

  public void execute(Lease lease) {
    try {
      var results = engine.reconcile(snapshots.load(lease.runId()).input());
      publish(lease, results);
    } catch (RuntimeException error) {
      try {
        leases.fail(lease);
      } catch (RuntimeException unavailable) {
        error.addSuppressed(unavailable);
      }
      throw error;
    }
  }

  /** Phase boundary for worker and fencing tests; never exposed over HTTP. */
  public void publish(Lease lease, List<ReconciliationEngine.Result> results) {
    tx.executeWithoutResult(
        t -> {
          leases.verify(lease);
          for (var result : results) {
            UUID resultId = UUID.randomUUID();
            jdbc.update(
                "INSERT INTO recon_result(id,run_id,execution_id,status,reason_codes,evidence_json,evidence_hash) VALUES (?,?,?,?,?::jsonb,?::jsonb,?)",
                resultId,
                lease.runId(),
                result.key().executionId(),
                result.status().name(),
                json.json(result.reasons()),
                json.json(result.evidence()),
                result.evidenceHash());
            cases.apply(lease.runId(), resultId, result);
          }
          jdbc.update(
              "UPDATE recon_run SET status='SUCCEEDED',finished_at=? WHERE id=?",
              Timestamp.from(clock.instant()),
              lease.runId());
          leases.audit(
              lease.runId(),
              "SYSTEM",
              "RUN_SUCCEEDED",
              Map.of("status", "RUNNING"),
              Map.of("status", "SUCCEEDED", "results", results.size()));
        });
  }
}
