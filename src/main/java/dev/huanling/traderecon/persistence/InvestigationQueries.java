package dev.huanling.traderecon.persistence;

import dev.huanling.traderecon.domain.*;
import dev.huanling.traderecon.ingestion.ImportFault;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class InvestigationQueries {
  public record Page(List<Map<String, Object>> items, int page, int size, long total) {
    public boolean hasNext() {
      return (long) (page + 1) * size < total;
    }
  }

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;

  public InvestigationQueries(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    named = new NamedParameterJdbcTemplate(jdbc);
  }

  public List<Map<String, Object>> datasets() {
    return jdbc.queryForList(
        "SELECT id,name FROM dataset WHERE kind='DEMO' ORDER BY name LIMIT 200");
  }

  public UUID createDataset(String name) {
    if (name == null || name.isBlank() || name.length() > 80)
      throw ImportFault.of(
          400, "INVALID_NAME", 0, "name", "Enter a dataset name of 1-80 characters");
    UUID id = UUID.randomUUID();
    try {
      jdbc.update("INSERT INTO dataset(id,name,kind) VALUES (?,?,'DEMO')", id, name.strip());
    } catch (org.springframework.dao.DuplicateKeyException e) {
      throw ImportFault.of(409, "NAME_EXISTS", 0, "name", "Choose a different dataset name");
    }
    return id;
  }

  public static final String CASE_FROM =
      " FROM exception_case c JOIN recon_result r ON r.id=c.current_result_id JOIN recon_run rr ON rr.id=r.run_id WHERE c.dataset_id=:dataset AND rr.status='SUCCEEDED'";
  public static final String CASE_ORDER =
      " ORDER BY CASE WHEN r.status='DATA_BLOCKED' THEN 0 WHEN jsonb_exists_any(r.reason_codes,ARRAY['ACCOUNT_MISMATCH','CURRENCY_MISMATCH','INSTRUMENT_MISMATCH']) THEN 1 ELSE 2 END,c.created_at,c.id";

  public Page cases(
      UUID dataset,
      LocalDate date,
      ReviewState review,
      ReconStatus status,
      String execution,
      int page,
      int size) {
    Map<String, Object> args = new HashMap<>();
    args.put("dataset", dataset);
    args.put("limit", size);
    args.put("offset", (long) page * size);
    String where = CASE_FROM;
    if (date != null) {
      where += " AND c.business_date=:date";
      args.put("date", date);
    }
    if (review != null) {
      where += " AND c.review_state=:review";
      args.put("review", review.name());
    }
    if (status != null) {
      where += " AND r.status=:status";
      args.put("status", status.name());
    }
    if (execution != null && !execution.isBlank()) {
      if (execution.length() > 64)
        throw ImportFault.of(400, "INVALID_FILTER", 0, "execution", "Maximum 64 characters");
      where += " AND c.execution_id=:execution";
      args.put("execution", execution);
    }
    long total = named.queryForObject("SELECT count(*)" + where, args, Long.class);
    return new Page(
        named.queryForList(
            "SELECT c.*,r.status,r.reason_codes,CASE WHEN r.status='DATA_BLOCKED' THEN 'DATA' WHEN jsonb_exists_any(r.reason_codes,ARRAY['ACCOUNT_MISMATCH','CURRENCY_MISMATCH','INSTRUMENT_MISMATCH']) THEN 'HIGH' ELSE 'NORMAL' END AS priority"
                + where
                + CASE_ORDER
                + " LIMIT :limit OFFSET :offset",
            args),
        page,
        size,
        total);
  }

  public Page runs(UUID dataset, LocalDate date, String status, int page, int size) {
    var args = new HashMap<String, Object>();
    args.put("dataset", dataset);
    args.put("limit", size);
    args.put("offset", (long) page * size);
    String where = " FROM recon_run WHERE dataset_id=:dataset";
    if (date != null) {
      where += " AND business_date=:date";
      args.put("date", date);
    }
    if (status != null && !status.isBlank()) {
      if (!Set.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED").contains(status))
        throw ImportFault.of(400, "INVALID_FILTER", 0, "status", "Choose a valid run status");
      where += " AND status=:status";
      args.put("status", status);
    }
    return new Page(
        named.queryForList(
            "SELECT id,business_date,status,as_of,rules_version"
                + where
                + " ORDER BY as_of DESC,id LIMIT :limit OFFSET :offset",
            args),
        page,
        size,
        named.queryForObject("SELECT count(*)" + where, args, Long.class));
  }

  public Page batches(
      UUID dataset, LocalDate date, Source source, String status, int page, int size) {
    var args = new HashMap<String, Object>();
    args.put("dataset", dataset);
    args.put("limit", size);
    args.put("offset", (long) page * size);
    String where = " FROM import_batch WHERE dataset_id=:dataset";
    if (date != null) {
      where += " AND business_date=:date";
      args.put("date", date);
    }
    if (source != null) {
      where += " AND source=:source";
      args.put("source", source.name());
    }
    if (status != null && !status.isBlank()) {
      if (!Set.of("ACCEPTED", "REJECTED").contains(status))
        throw ImportFault.of(400, "INVALID_FILTER", 0, "status", "Choose ACCEPTED or REJECTED");
      where += " AND status=:status";
      args.put("status", status);
    }
    return new Page(
        named.queryForList(
            "SELECT id,source,business_date,status,feed_complete,row_counts,error_summary,created_at"
                + where
                + " ORDER BY created_at DESC,id LIMIT :limit OFFSET :offset",
            args),
        page,
        size,
        named.queryForObject("SELECT count(*)" + where, args, Long.class));
  }

  /** Timeline is scoped to the result's frozen run, not the latest uploaded revisions. */
  public Page timeline(UUID run, String execution, int page, int size) {
    String where =
        " FROM run_batch rb JOIN import_row ir ON ir.batch_id=rb.batch_id JOIN trade_event e ON e.id=ir.event_ref WHERE rb.run_id=? AND e.execution_id=?";
    long total =
        jdbc.queryForObject("SELECT count(DISTINCT e.id)" + where, Long.class, run, execution);
    return new Page(
        jdbc.queryForList(
            "SELECT DISTINCT e.id,e.source,e.event_id,e.revision,e.event_type,e.quantity,e.price,e.occurred_at,e.received_at,e.note,e.first_batch_id"
                + where
                + " ORDER BY e.source,e.revision,e.id LIMIT ? OFFSET ?",
            run,
            execution,
            size,
            (long) page * size),
        page,
        size,
        total);
  }

  public List<Map<String, Object>> runBatches(UUID run) {
    return jdbc.queryForList(
        "SELECT b.id,b.source,b.business_date,b.feed_complete,b.accepted_at,b.row_counts FROM run_batch rb JOIN import_batch b ON b.id=rb.batch_id WHERE rb.run_id=? ORDER BY b.source,b.accepted_at,b.id",
        run);
  }
}
