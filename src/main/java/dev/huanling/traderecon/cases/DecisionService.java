package dev.huanling.traderecon.cases;

import dev.huanling.traderecon.ingestion.ImportFault;
import dev.huanling.traderecon.persistence.ImportRepository;
import dev.huanling.traderecon.security.DemoActors;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DecisionService {
  public enum Action {
    CLAIM,
    ESCALATE,
    CLOSE
  }

  private final JdbcTemplate jdbc;
  private final DemoActors actors;
  private final ImportRepository json;
  private final Clock clock;
  private final TransactionTemplate tx;

  public DecisionService(
      JdbcTemplate jdbc,
      DemoActors actors,
      ImportRepository json,
      Clock clock,
      PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.actors = actors;
    this.json = json;
    this.clock = clock;
    tx = new TransactionTemplate(manager);
    tx.setTimeout(30);
  }

  public Map<String, Object> decide(
      String username,
      UUID id,
      long expectedVersion,
      UUID resultId,
      String evidenceHash,
      Action action,
      String reason) {
    var actor = actors.writer(username);
    if (action == null
        || resultId == null
        || expectedVersion < 0
        || evidenceHash == null
        || !evidenceHash.matches("[0-9a-f]{64}")
        || reason == null
        || reason.isBlank()
        || reason.codePointCount(0, reason.length()) > 500)
      throw ImportFault.of(
          400,
          "INVALID_DECISION",
          0,
          "reason",
          "Valid version, result, evidence and 1-500 character nonblank reason required");
    if (action == Action.CLOSE && !actor.role().equals("SUPERVISOR"))
      throw ImportFault.of(403, "FORBIDDEN", 0, "action", "Only supervisor may close a case");
    return tx.execute(
        t -> {
          var rows = jdbc.queryForList("SELECT * FROM exception_case WHERE id=? FOR UPDATE", id);
          if (rows.isEmpty()) throw ImportFault.of(404, "NOT_FOUND", 0, "id", "Case not found");
          var c = rows.getFirst();
          String currentHash =
              jdbc.queryForObject(
                  "SELECT evidence_hash FROM recon_result WHERE id=?",
                  String.class,
                  c.get("current_result_id"));
          if (((Number) c.get("version")).longValue() != expectedVersion
              || !resultId.equals(c.get("current_result_id"))
              || !evidenceHash.equals(currentHash))
            throw ImportFault.of(
                409,
                "STALE_EVIDENCE",
                0,
                "expectedVersion",
                "Case or evidence changed; reload before deciding");
          String before = c.get("review_state").toString();
          String after;
          switch (action) {
            case CLAIM -> {
              if (!before.equals("OPEN")) throw transition();
              after = "IN_REVIEW";
            }
            case ESCALATE -> {
              if (!before.equals("IN_REVIEW")) throw transition();
              if (!actor.role().equals("SUPERVISOR") && !actor.id().equals(c.get("assignee")))
                throw ImportFault.of(
                    403, "FORBIDDEN", 0, "assignee", "Only assignee or supervisor may escalate");
              after = "ESCALATED";
            }
            case CLOSE -> {
              if (!Set.of("OPEN", "IN_REVIEW", "ESCALATED").contains(before)) throw transition();
              after = "CLOSED_WITH_REASON";
            }
            default -> throw transition();
          }
          UUID assignee = action == Action.CLAIM ? actor.id() : (UUID) c.get("assignee");
          int changed =
              jdbc.update(
                  "UPDATE exception_case SET review_state=?,assignee=?,version=version+1,updated_at=? WHERE id=? AND version=? AND current_result_id=?",
                  after,
                  assignee,
                  Timestamp.from(clock.instant()),
                  id,
                  expectedVersion,
                  resultId);
          if (changed != 1)
            throw ImportFault.of(
                409, "STALE_EVIDENCE", 0, "expectedVersion", "Concurrent update; reload");
          jdbc.update(
              "INSERT INTO audit_event(id,case_id,actor,action,before_state,after_state,reason,created_at) VALUES (?,?,?,?,?::jsonb,?::jsonb,?,?)",
              UUID.randomUUID(),
              id,
              actor.name(),
              "MANUAL_" + action.name(),
              json.json(
                  Map.of(
                      "reviewState",
                      before,
                      "version",
                      expectedVersion,
                      "resultId",
                      resultId,
                      "evidenceHash",
                      evidenceHash)),
              json.json(
                  Map.of(
                      "reviewState",
                      after,
                      "version",
                      expectedVersion + 1,
                      "resultId",
                      resultId,
                      "evidenceHash",
                      evidenceHash)),
              reason.strip(),
              Timestamp.from(clock.instant()));
          return Map.of("id", id, "reviewState", after, "version", expectedVersion + 1);
        });
  }

  private ImportFault transition() {
    return ImportFault.of(
        409,
        "INVALID_TRANSITION",
        0,
        "action",
        "Action is not allowed in the current review state");
  }
}
