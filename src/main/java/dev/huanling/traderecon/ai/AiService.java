package dev.huanling.traderecon.ai;

import com.fasterxml.jackson.databind.*;
import dev.huanling.traderecon.ingestion.ImportFault;
import dev.huanling.traderecon.persistence.ReconciliationQueries;
import dev.huanling.traderecon.security.DemoActors;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AiService {
  public record Submission(UUID id, boolean cached, String status) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final AiSettings config;
  private final AiEvidence evidence;
  private final AiOutputValidator validator;
  private final ReconciliationQueries queries;
  private final DemoActors actors;
  private final TransactionTemplate tx;
  private final AiProviderFactory providers;

  public AiService(
      JdbcTemplate j,
      ObjectMapper mapper,
      AiSettings c,
      AiEvidence e,
      AiOutputValidator v,
      ReconciliationQueries q,
      DemoActors a,
      PlatformTransactionManager tm,
      AiProviderFactory providers) {
    jdbc = j;
    json = mapper;
    config = c;
    evidence = e;
    validator = v;
    queries = q;
    actors = a;
    tx = new TransactionTemplate(tm);
    tx.setTimeout(10);
    this.providers = providers;
  }

  public String mode() {
    return config.mode();
  }

  private void lock() {
    jdbc.execute("SELECT pg_advisory_xact_lock(73420617)");
  }

  public Submission submit(String actor, UUID caseId, UUID resultId, String hash) {
    actors.writer(actor);
    return tx.execute(
        t -> {
          lock();
          var current = queries.caseDetail(caseId, 0, 1);
          if (!resultId.equals(current.get("current_result_id"))
              || !hash.equals(current.get("evidence_hash")))
            throw ImportFault.of(
                409,
                "STALE_EVIDENCE",
                0,
                "resultId",
                "Reload the case and review its new evidence");
          var cached =
              jdbc.queryForList(
                  "SELECT id,status FROM ai_analysis WHERE case_id=? AND evidence_hash=? AND prompt_version=? AND provider=? AND model=? AND status IN ('QUEUED','RUNNING','SUCCEEDED') ORDER BY created_at DESC LIMIT 1",
                  caseId,
                  hash,
                  config.promptVersion(),
                  config.mode(),
                  config.model());
          if (!cached.isEmpty())
            return new Submission(
                (UUID) cached.getFirst().get("id"),
                true,
                cached.getFirst().get("status").toString());
          var input =
              evidence.create(caseId, resultId, hash, (JsonNode) current.get("evidence_json"));
          evidence.timeline(
              input,
              jdbc.queryForList(
                  "SELECT DISTINCT e.source,e.revision,e.occurred_at,e.received_at FROM run_batch rb JOIN import_row ir ON ir.batch_id=rb.batch_id JOIN trade_event e ON e.id=ir.event_ref WHERE rb.run_id=? AND e.execution_id=? ORDER BY e.source,e.revision LIMIT 41",
                  current.get("run_id"),
                  current.get("execution_id")));
          String failure = config.unavailable();
          if (input.path("contextIncomplete").asBoolean()) failure = "CONTEXT_INCOMPLETE";
          BigDecimal reserve = BigDecimal.ZERO;
          if (failure == null && config.mode().equals("LIVE")) {
            var provider = new LiveInvestigationProvider(json, config.key(), config.model());
            reserve =
                config.reservation(
                    provider.request(input).toString().getBytes(StandardCharsets.UTF_8).length);
            BigDecimal used =
                jdbc.queryForObject(
                    "SELECT coalesce(sum(reserved_usd),0) FROM ai_analysis WHERE provider='LIVE'",
                    BigDecimal.class);
            long requests =
                jdbc.queryForObject(
                    "SELECT count(*) FROM ai_analysis WHERE provider='LIVE' AND reserved_usd>0",
                    Long.class);
            if (used.add(reserve).compareTo(config.budget()) > 0
                || requests >= config.maxRequests()) {
              failure = "BUDGET_LIMIT";
              reserve = BigDecimal.ZERO;
            }
          }
          if (failure == null
              && jdbc.queryForObject(
                      "SELECT count(*) FROM ai_analysis WHERE status='QUEUED'", Integer.class)
                  >= 20)
            throw ImportFault.of(429, "AI_QUEUE_FULL", 0, "ai", "Use the template and retry later");
          UUID id = UUID.randomUUID();
          jdbc.update(
              "INSERT INTO ai_analysis(id,case_id,result_id,evidence_hash,prompt_version,provider,model,status,input_json,validation_errors,reserved_usd,actor) VALUES (?,?,?,?,?,?,?, ?,?::jsonb,?::jsonb,?,?)",
              id,
              caseId,
              resultId,
              hash,
              config.promptVersion(),
              config.mode(),
              config.model(),
              failure == null ? "QUEUED" : "FAILED",
              input.toString(),
              failure == null ? null : encode(List.of(failure)),
              reserve,
              actor);
          return new Submission(id, false, failure == null ? "QUEUED" : "FAILED");
        });
  }

  public Map<String, Object> get(UUID id) {
    var rows =
        jdbc.queryForList(
            "SELECT a.*,c.evidence_hash current_hash FROM ai_analysis a JOIN (SELECT c.id,r.evidence_hash FROM exception_case c JOIN recon_result r ON r.id=c.current_result_id) c ON c.id=a.case_id WHERE a.id=?",
            id);
    if (rows.isEmpty()) throw ImportFault.of(404, "NOT_FOUND", 0, "id", "Analysis not found");
    var row = rows.getFirst();
    for (String key : List.of("input_json", "response_json", "validation_errors", "token_usage"))
      if (row.get(key) != null) row.put(key, parse(row.get(key).toString()));
    boolean stale = !row.get("evidence_hash").equals(row.get("current_hash"));
    if (stale) {
      row.put("status", "STALE");
      row.put("response_json", null);
    }
    if (!row.get("status").equals("SUCCEEDED")) row.put("response_json", null);
    row.remove("raw_response");
    row.remove("owner_token");
    row.remove("current_hash");
    row.put("humanReviewRequired", true);
    row.put("mode", row.get("provider"));
    return row;
  }

  public List<Map<String, Object>> latest(UUID caseId) {
    return jdbc
        .queryForList(
            "SELECT id FROM ai_analysis WHERE case_id=? ORDER BY created_at DESC,id DESC LIMIT 10",
            caseId)
        .stream()
        .map(x -> get((UUID) x.get("id")))
        .toList();
  }

  /** Durable queue claims: global two-request cap; abandoned calls never automatically rebilled. */
  public Map<String, Object> claim() {
    return tx.execute(
        t -> {
          lock();
          jdbc.update(
              "UPDATE ai_analysis SET status='FAILED',validation_errors='[\"INTERRUPTED_OR_TIMEOUT\"]'::jsonb,finished_at=now() WHERE status='RUNNING' AND started_at<now()-interval '30 seconds'");
          if (jdbc.queryForObject(
                  "SELECT count(*) FROM ai_analysis WHERE status='RUNNING'", Integer.class)
              >= 2) return null;
          var rows =
              jdbc.queryForList(
                  "SELECT * FROM ai_analysis WHERE status='QUEUED' AND provider=? AND model=? ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",
                  config.mode(),
                  config.model());
          if (rows.isEmpty()) return null;
          var row = rows.getFirst();
          UUID token = UUID.randomUUID();
          jdbc.update(
              "UPDATE ai_analysis SET status='RUNNING',owner_token=?,started_at=now() WHERE id=?",
              token,
              row.get("id"));
          row.put("owner_token", token);
          return row;
        });
  }

  public void execute(Map<String, Object> row) {
    long start = System.nanoTime();
    String raw = null, request = null, actual = null, error = null;
    JsonNode valid = null, usage = null;
    int attempts = 0;
    BigDecimal cost = null;
    try {
      if (!config.mode().equals(row.get("provider"))
          || !config.model().equals(row.get("model"))
          || config.unavailable() != null) throw new IllegalStateException("CONFIGURATION_CHANGED");
      if (!get((UUID) row.get("id")).get("status").equals("RUNNING"))
        throw new IllegalStateException("STALE_OR_EXPIRED");
      JsonNode input = parse(row.get("input_json").toString());
      InvestigationProvider provider = providers.create();
      if (provider instanceof LiveInvestigationProvider live
          && config
                  .reservation(
                      live.request(input).toString().getBytes(StandardCharsets.UTF_8).length)
                  .compareTo((BigDecimal) row.get("reserved_usd"))
              > 0) throw new IllegalStateException("BUDGET_CONFIGURATION_CHANGED");
      var response = provider.analyse(input, Duration.ofSeconds(20));
      raw = response.raw();
      request = response.requestId();
      actual = response.model();
      usage = response.usage();
      attempts = response.attempts();
      valid = validator.validate(raw, input);
      if (config.mode().equals("LIVE")
          && usage != null
          && usage.has("prompt_tokens")
          && usage.has("completion_tokens"))
        cost =
            config
                .inputRate()
                .multiply(BigDecimal.valueOf(usage.path("prompt_tokens").asLong()))
                .add(
                    config
                        .outputRate()
                        .multiply(BigDecimal.valueOf(usage.path("completion_tokens").asLong())))
                .divide(BigDecimal.valueOf(1000000), 6, java.math.RoundingMode.UP);
    } catch (Exception e) {
      error = e instanceof IllegalArgumentException ? "INVALID_AI_OUTPUT" : "PROVIDER_UNAVAILABLE";
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
    }
    long latency = (System.nanoTime() - start) / 1000000;
    jdbc.update(
        "UPDATE ai_analysis SET status=?,raw_response=?,response_json=?::jsonb,validation_errors=?::jsonb,request_id=?,actual_model=?,token_usage=?::jsonb,attempts=?,latency_ms=?,estimated_cost=?,finished_at=now() WHERE id=? AND owner_token=? AND status='RUNNING'",
        error == null ? "SUCCEEDED" : "FAILED",
        raw == null ? null : raw.substring(0, Math.min(raw.length(), 16000)),
        valid == null ? null : valid.toString(),
        error == null ? null : encode(List.of(error)),
        request,
        actual,
        usage == null ? null : usage.toString(),
        attempts,
        latency,
        cost,
        row.get("id"),
        row.get("owner_token"));
  }

  private String encode(Object o) {
    try {
      return json.writeValueAsString(o);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private JsonNode parse(String s) {
    try {
      return json.readTree(s);
    } catch (Exception e) {
      throw new IllegalStateException("Stored JSON invalid");
    }
  }
}
