package dev.huanling.traderecon.reconciliation;

import dev.huanling.traderecon.domain.ReconStatus;
import dev.huanling.traderecon.persistence.ImportRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Called inside the result-publication transaction; always reads the current locked case. */
@Service
public class CaseService {
  private final JdbcTemplate jdbc;
  private final ImportRepository json;
  private final Clock clock;

  public CaseService(JdbcTemplate jdbc, ImportRepository json, Clock clock) {
    this.jdbc = jdbc;
    this.json = json;
    this.clock = clock;
  }

  public void apply(UUID run, UUID resultId, ReconciliationEngine.Result result) {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Case updates require publication transaction");
    var key = result.key();
    var found =
        jdbc.queryForList(
            "SELECT * FROM exception_case WHERE dataset_id=? AND business_date=? AND execution_id=? FOR UPDATE",
            key.datasetId(),
            key.tradeDate(),
            key.executionId());
    boolean discrepancy =
        result.status() == ReconStatus.BREAK || result.status() == ReconStatus.DATA_BLOCKED;
    if (found.isEmpty()) {
      if (!discrepancy) return;
      UUID id = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO exception_case(id,dataset_id,business_date,execution_id,current_result_id,review_state,created_at,updated_at) VALUES (?,?,?,?,?,'OPEN',?,?)",
          id,
          key.datasetId(),
          key.tradeDate(),
          key.executionId(),
          resultId,
          Timestamp.from(clock.instant()),
          Timestamp.from(clock.instant()));
      audit(id, run, "CASE_CREATED", Map.of(), Map.of("reviewState", "OPEN", "resultId", resultId));
      return;
    }
    var current = found.getFirst();
    String before = current.get("review_state").toString();
    String after = before;
    String previousHash =
        jdbc.queryForObject(
            "SELECT evidence_hash FROM recon_result WHERE id=?",
            String.class,
            current.get("current_result_id"));
    boolean changed = !previousHash.equals(result.evidenceHash());
    if (result.status() == ReconStatus.MATCHED) after = "RESOLVED_BY_DATA";
    else if (discrepancy
        && (before.equals("RESOLVED_BY_DATA") || (before.equals("CLOSED_WITH_REASON") && changed)))
      after = "OPEN";
    // WAITING is not proof of resolution; preserve manual state. Exact reruns do not bump version.
    if (!changed && before.equals(after)) return;
    UUID id = (UUID) current.get("id");
    jdbc.update(
        "UPDATE exception_case SET current_result_id=?,review_state=?,version=version+1,updated_at=? WHERE id=?",
        resultId,
        after,
        Timestamp.from(clock.instant()),
        id);
    audit(
        id,
        run,
        after.equals(before)
            ? "EVIDENCE_UPDATED"
            : after.equals("RESOLVED_BY_DATA") ? "RESOLVED_BY_DATA" : "CASE_REOPENED",
        Map.of("reviewState", before, "resultId", current.get("current_result_id")),
        Map.of("reviewState", after, "resultId", resultId));
  }

  private void audit(UUID id, UUID run, String action, Object before, Object after) {
    jdbc.update(
        "INSERT INTO audit_event(id,case_id,run_id,actor,action,before_state,after_state,reason,created_at) VALUES (?,?,?,'SYSTEM',?,?::jsonb,?::jsonb,'Deterministic reconciliation',?)",
        UUID.randomUUID(),
        id,
        run,
        action,
        json.json(before),
        json.json(after),
        Timestamp.from(clock.instant()));
  }
}
