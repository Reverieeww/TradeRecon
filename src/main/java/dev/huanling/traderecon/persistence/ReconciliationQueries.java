package dev.huanling.traderecon.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.huanling.traderecon.domain.ReconStatus;
import dev.huanling.traderecon.ingestion.ImportFault;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReconciliationQueries {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public ReconciliationQueries(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private List<Map<String, Object>> query(String sql, Object... args) {
    var rows = jdbc.queryForList(sql, args);
    for (var row : rows)
      for (var entry : row.entrySet()) {
        Object value = entry.getValue();
        if (value != null && value.getClass().getName().equals("org.postgresql.util.PGobject")) {
          try {
            entry.setValue(json.readTree(value.toString()));
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        }
      }
    return rows;
  }

  public List<Map<String, Object>> runs(UUID dataset, int page, int size) {
    return query(
        "SELECT id,business_date,status,as_of,rules_version,finished_at FROM recon_run WHERE dataset_id=? ORDER BY as_of DESC,id LIMIT ? OFFSET ?",
        dataset,
        size,
        (long) page * size);
  }

  public Map<String, Object> run(UUID id, int page, int size) {
    var rows = query("SELECT * FROM recon_run WHERE id=?", id);
    if (rows.isEmpty()) throw ImportFault.of(404, "NOT_FOUND", 0, "id", "Run not found");
    var run = rows.getFirst();
    var complete =
        jdbc.queryForList(
            "SELECT DISTINCT b.source FROM run_batch rb JOIN import_batch b ON b.id=rb.batch_id WHERE rb.run_id=? AND b.feed_complete ORDER BY b.source",
            String.class,
            id);
    var diagnostic = (com.fasterxml.jackson.databind.JsonNode) run.get("snapshot_diagnostics");
    run.put("completeSources", complete);
    run.put(
        "completeness",
        complete.size() == 2 && diagnostic.path("rejectedBatchIds").isEmpty()
            ? "COMPLETE"
            : "PROVISIONAL");
    run.put(
        "batchIds",
        jdbc.queryForList(
            "SELECT batch_id FROM run_batch WHERE run_id=? ORDER BY batch_id", UUID.class, id));
    Map<String, Long> counts = new LinkedHashMap<>();
    for (var status : ReconStatus.values()) counts.put(status.name(), 0L);
    if ("SUCCEEDED".equals(run.get("status"))) {
      for (var count :
          jdbc.queryForList(
              "SELECT status,count(*) n FROM recon_result WHERE run_id=? GROUP BY status", id))
        counts.put(count.get("status").toString(), ((Number) count.get("n")).longValue());
      run.put(
          "results",
          query(
              "SELECT * FROM recon_result WHERE run_id=? ORDER BY execution_id LIMIT ? OFFSET ?",
              id,
              size,
              (long) page * size));
    } else run.put("results", List.of());
    run.put("counts", counts);
    run.put("total", counts.values().stream().mapToLong(Long::longValue).sum());
    return run;
  }

  public List<Map<String, Object>> cases(UUID dataset, int page, int size) {
    return query(
        "SELECT c.*,r.status,r.reason_codes FROM exception_case c JOIN recon_result r ON r.id=c.current_result_id JOIN recon_run rr ON rr.id=r.run_id WHERE c.dataset_id=? AND rr.status='SUCCEEDED' ORDER BY c.updated_at DESC,c.id LIMIT ? OFFSET ?",
        dataset,
        size,
        (long) page * size);
  }

  public Map<String, Object> caseDetail(UUID id, int page, int size) {
    var rows =
        query(
            "SELECT c.*,r.status,r.reason_codes,r.evidence_json,r.evidence_hash,r.run_id,rr.as_of,rr.rules_version FROM exception_case c JOIN recon_result r ON r.id=c.current_result_id JOIN recon_run rr ON rr.id=r.run_id WHERE c.id=? AND rr.status='SUCCEEDED'",
            id);
    if (rows.isEmpty()) throw ImportFault.of(404, "NOT_FOUND", 0, "id", "Case not found");
    var result = rows.getFirst();
    result.put(
        "auditTotal",
        jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE case_id=?", Long.class, id));
    result.put(
        "audit",
        query(
            "SELECT * FROM audit_event WHERE case_id=? ORDER BY created_at,id LIMIT ? OFFSET ?",
            id,
            size,
            (long) page * size));
    return result;
  }
}
