package dev.huanling.traderecon.jobs;

import dev.huanling.traderecon.ingestion.ImportFault;
import dev.huanling.traderecon.persistence.ImportRepository;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LeaseService {
  public static final Duration TIMEOUT = Duration.ofSeconds(60);

  public record Lease(UUID runId, UUID token) {}

  private final JdbcTemplate jdbc;
  private final ImportRepository json;
  private final Clock clock;
  private final TransactionTemplate tx;

  public LeaseService(
      JdbcTemplate jdbc, ImportRepository json, Clock clock, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.json = json;
    this.clock = clock;
    tx = new TransactionTemplate(manager);
    tx.setTimeout(30);
  }

  private Timestamp now() {
    return Timestamp.from(clock.instant());
  }

  private Timestamp cutoff() {
    return Timestamp.from(clock.instant().minus(TIMEOUT));
  }

  public Optional<Lease> claimNext() {
    return tx.execute(
        t -> {
          // Global single-worker admission even if a second app instance is accidentally started.
          jdbc.query("SELECT pg_advisory_xact_lock(7140304)", r -> {});
          if (jdbc.queryForObject(
                  "SELECT count(*) FROM recon_run WHERE status='RUNNING' AND heartbeat_at>=?",
                  Integer.class,
                  cutoff())
              > 0) return Optional.empty();
          var rows =
              jdbc.queryForList(
                  "SELECT id FROM recon_run WHERE status='QUEUED' OR (status='RUNNING' AND (heartbeat_at IS NULL OR heartbeat_at<?)) ORDER BY as_of,id FOR UPDATE SKIP LOCKED LIMIT 1",
                  UUID.class,
                  cutoff());
          return rows.isEmpty() ? Optional.empty() : Optional.of(claimLocked(rows.getFirst()));
        });
  }

  /** Internal deterministic test/service entry point; not exposed as an HTTP operation. */
  public Lease claim(UUID id) {
    return tx.execute(t -> claimLocked(id));
  }

  private Lease claimLocked(UUID id) {
    var rows =
        jdbc.queryForList(
            "SELECT status,owner_token,heartbeat_at FROM recon_run WHERE id=? FOR UPDATE", id);
    if (rows.isEmpty()) throw ImportFault.of(404, "NOT_FOUND", 0, "id", "Run not found");
    var row = rows.getFirst();
    String status = row.get("status").toString();
    var beat = (Timestamp) row.get("heartbeat_at");
    boolean stale = status.equals("RUNNING") && (beat == null || beat.before(cutoff()));
    if (!status.equals("QUEUED") && !stale)
      throw ImportFault.of(409, "RUN_NOT_CLAIMABLE", 0, "id", "Run is not queued or expired");
    UUID token = UUID.randomUUID();
    jdbc.update(
        "UPDATE recon_run SET status='RUNNING',owner_token=?,started_at=COALESCE(started_at,?),heartbeat_at=?,finished_at=NULL,error=NULL WHERE id=?",
        token,
        now(),
        now(),
        id);
    audit(
        id,
        "SYSTEM",
        stale ? "RUN_RECOVERED" : "RUN_CLAIMED",
        Map.of("status", status),
        Map.of("status", "RUNNING", "ownerToken", token));
    return new Lease(id, token);
  }

  public boolean heartbeat(Lease lease) {
    return jdbc.update(
            "UPDATE recon_run SET heartbeat_at=? WHERE id=? AND owner_token=? AND status='RUNNING' AND heartbeat_at>=?",
            now(),
            lease.runId(),
            lease.token(),
            cutoff())
        == 1;
  }

  /** Must run inside the publication transaction; locks and fences the publisher. */
  public void verify(Lease lease) {
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive())
      throw new IllegalStateException("Publication requires transaction");
    var rows =
        jdbc.queryForList(
            "SELECT status,owner_token,heartbeat_at FROM recon_run WHERE id=? FOR UPDATE",
            lease.runId());
    if (rows.isEmpty()) throw ImportFault.of(404, "NOT_FOUND", 0, "id", "Run not found");
    var row = rows.getFirst();
    var heartbeat = (Timestamp) row.get("heartbeat_at");
    if (!"RUNNING".equals(row.get("status"))
        || !lease.token().equals(row.get("owner_token"))
        || heartbeat == null
        || heartbeat.before(cutoff()))
      throw ImportFault.of(
          409, "STALE_OWNER", 0, "ownerToken", "Worker lease expired or was replaced");
  }

  public UUID retry(UUID id, String actor) {
    try {
      return tx.execute(
          t -> {
            var identity =
                jdbc.queryForList("SELECT dataset_id,business_date FROM recon_run WHERE id=?", id);
            if (identity.isEmpty())
              throw ImportFault.of(404, "NOT_FOUND", 0, "id", "Run not found");
            jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,71404))",
                r -> {},
                identity.getFirst().get("dataset_id")
                    + ":"
                    + identity.getFirst().get("business_date"));
            var rows = jdbc.queryForList("SELECT * FROM recon_run WHERE id=? FOR UPDATE", id);
            if (rows.isEmpty()) throw ImportFault.of(404, "NOT_FOUND", 0, "id", "Run not found");
            var r = rows.getFirst();
            String state = r.get("status").toString();
            if (state.equals("SUCCEEDED") || state.equals("QUEUED")) return id;
            var beat = (Timestamp) r.get("heartbeat_at");
            if (state.equals("RUNNING") && beat != null && !beat.before(cutoff()))
              throw ImportFault.of(409, "RUN_ACTIVE", 0, "id", "Worker is still active");
            if (jdbc.queryForObject(
                    "SELECT count(*) FROM recon_run WHERE dataset_id=? AND business_date=? AND id<>? AND created_at>? AND status IN ('QUEUED','RUNNING','SUCCEEDED')",
                    Integer.class,
                    r.get("dataset_id"),
                    r.get("business_date"),
                    id,
                    r.get("created_at"))
                > 0)
              throw ImportFault.of(
                  409,
                  "STALE_RUN",
                  0,
                  "id",
                  "A newer run exists; retrying this snapshot could regress current cases");
            jdbc.update(
                "UPDATE recon_run SET status='QUEUED',owner_token=NULL,heartbeat_at=NULL,finished_at=NULL,error=NULL WHERE id=?",
                id);
            audit(id, actor, "RUN_REQUEUED", Map.of("status", state), Map.of("status", "QUEUED"));
            return id;
          });
    } catch (org.springframework.dao.DuplicateKeyException e) {
      throw ImportFault.of(409, "RUN_ACTIVE", 0, "id", "Another active run exists for this date");
    }
  }

  public void fail(Lease lease) {
    tx.executeWithoutResult(
        t -> {
          int updated =
              jdbc.update(
                  "UPDATE recon_run SET status='FAILED',finished_at=?,error=?::jsonb WHERE id=? AND owner_token=? AND status='RUNNING' AND heartbeat_at>=?",
                  now(),
                  json.json(
                      Map.of(
                          "code",
                          "RUN_FAILED",
                          "message",
                          "Execution failed; retry uses the same frozen input")),
                  lease.runId(),
                  lease.token(),
                  cutoff());
          if (updated == 1)
            audit(
                lease.runId(),
                "SYSTEM",
                "RUN_FAILED",
                Map.of("status", "RUNNING"),
                Map.of("status", "FAILED"));
        });
  }

  public void audit(UUID id, String actor, String action, Object before, Object after) {
    jdbc.update(
        "INSERT INTO audit_event(id,run_id,actor,action,before_state,after_state,reason,created_at) VALUES (?,?,?,?,?::jsonb,?::jsonb,?,?)",
        UUID.randomUUID(),
        id,
        actor,
        action,
        json.json(before),
        json.json(after),
        action,
        now());
  }
}
