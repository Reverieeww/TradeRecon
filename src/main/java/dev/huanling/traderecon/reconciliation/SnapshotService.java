package dev.huanling.traderecon.reconciliation;

import com.fasterxml.jackson.databind.*;
import dev.huanling.traderecon.domain.*;
import dev.huanling.traderecon.domain.Currency;
import dev.huanling.traderecon.ingestion.*;
import dev.huanling.traderecon.persistence.ImportRepository;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SnapshotService {
  public record Snapshot(UUID id, ReconciliationEngine.Input input, JsonNode diagnostics) {}

  private final JdbcTemplate jdbc;
  private final ImportRepository imports;
  private final ObjectMapper json;
  private final Clock clock;
  private final TransactionTemplate snapshotTx;

  public SnapshotService(
      JdbcTemplate jdbc,
      ImportRepository imports,
      ObjectMapper json,
      Clock clock,
      PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.imports = imports;
    this.json = json;
    this.clock = clock;
    snapshotTx = new TransactionTemplate(manager);
    snapshotTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    snapshotTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public UUID create(UUID dataset, LocalDate date) {
    try {
      return snapshotTx.execute(tx -> freeze(dataset, date));
    } catch (DuplicateKeyException busy) {
      throw ImportFault.of(
          409,
          "RUN_ACTIVE",
          0,
          "businessDate",
          "An active run already exists for this dataset/date");
    }
  }

  /** Joins the submission transaction so its idempotency record commits with this snapshot. */
  public UUID freeze(UUID dataset, LocalDate date) {
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive())
      throw new IllegalStateException("Snapshot requires a transaction");
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,71404))", r -> {}, dataset + ":" + date);
    if (!imports.datasetExists(dataset))
      throw ImportFault.of(404, "NOT_FOUND", 0, "dataset", "Dataset not found");
    Instant asOf = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    var accepted =
        jdbc.queryForList(
            "SELECT id FROM import_batch WHERE dataset_id=? AND business_date=? AND status='ACCEPTED' ORDER BY id",
            UUID.class,
            dataset,
            date);
    var rejected =
        jdbc.queryForList(
            "SELECT id FROM import_batch WHERE dataset_id=? AND business_date=? AND status='REJECTED' ORDER BY id",
            UUID.class,
            dataset,
            date);
    UUID id = UUID.randomUUID();
    String hash =
        EventFingerprint.sha(
            (dataset + "|" + date + "|" + RuleSet.VERSION + "|" + asOf + "|" + accepted)
                .getBytes(StandardCharsets.UTF_8));
    jdbc.update(
        "INSERT INTO recon_run(id,dataset_id,business_date,rules_version,as_of,input_hash,status,snapshot_diagnostics) VALUES (?,?,?,?,?,?,'QUEUED',?::jsonb)",
        id,
        dataset,
        date,
        RuleSet.VERSION,
        Timestamp.from(asOf),
        hash,
        imports.json(Map.of("rejectedBatchIds", rejected)));
    for (UUID batch : accepted)
      jdbc.update("INSERT INTO run_batch(run_id,batch_id) VALUES (?,?)", id, batch);
    return id;
  }

  /** Reads only immutable batch associations; duplicate rows also reference accepted events. */
  public Snapshot load(UUID id) {
    var run = jdbc.queryForList("SELECT * FROM recon_run WHERE id=?", id);
    if (run.isEmpty()) throw ImportFault.of(404, "NOT_FOUND", 0, "id", "Run not found");
    var r = run.getFirst();
    UUID dataset = (UUID) r.get("dataset_id");
    LocalDate date = ((java.sql.Date) r.get("business_date")).toLocalDate();
    var first = new HashMap<ExecutionKey, Instant>();
    var events =
        jdbc.query(
            "SELECT DISTINCT e.* FROM run_batch rb JOIN import_row ir ON ir.batch_id=rb.batch_id JOIN trade_event e ON e.id=ir.event_ref WHERE rb.run_id=? ORDER BY e.source,e.execution_id,e.revision",
            (rs, n) -> {
              var key =
                  new ExecutionKey(
                      dataset,
                      rs.getDate("trade_date").toLocalDate(),
                      rs.getString("execution_id"));
              first.merge(
                  key, rs.getTimestamp("received_at").toInstant(), (a, b) -> a.isBefore(b) ? a : b);
              return new TradeEvent(
                  key,
                  Source.valueOf(rs.getString("source")),
                  rs.getString("event_id"),
                  rs.getInt("revision"),
                  EventType.valueOf(rs.getString("event_type")),
                  rs.getString("account_id"),
                  rs.getString("instrument_id"),
                  Side.valueOf(rs.getString("side")),
                  new MoneyFields(
                      rs.getBigDecimal("quantity"),
                      rs.getBigDecimal("price"),
                      Currency.valueOf(rs.getString("currency"))),
                  rs.getTimestamp("occurred_at").toInstant(),
                  rs.getString("note"));
            },
            id);
    Set<Source> complete = new HashSet<>();
    jdbc.queryForList(
            "SELECT DISTINCT b.source FROM run_batch rb JOIN import_batch b ON b.id=rb.batch_id WHERE rb.run_id=? AND b.feed_complete",
            String.class,
            id)
        .forEach(s -> complete.add(Source.valueOf(s)));
    JsonNode diagnostics;
    try {
      diagnostics = json.readTree(r.get("snapshot_diagnostics").toString());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return new Snapshot(
        id,
        new ReconciliationEngine.Input(
            dataset,
            date,
            ((Timestamp) r.get("as_of")).toInstant(),
            r.get("rules_version").toString(),
            events,
            first,
            complete),
        diagnostics);
  }
}
