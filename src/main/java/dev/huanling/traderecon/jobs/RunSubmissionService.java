package dev.huanling.traderecon.jobs;

import dev.huanling.traderecon.ingestion.*;
import dev.huanling.traderecon.reconciliation.SnapshotService;
import dev.huanling.traderecon.security.DemoActors;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import org.springframework.dao.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RunSubmissionService {
  private final JdbcTemplate jdbc;
  private final SnapshotService snapshots;
  private final DemoActors actors;
  private final TransactionTemplate tx;

  public RunSubmissionService(
      JdbcTemplate jdbc,
      SnapshotService snapshots,
      DemoActors actors,
      PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.snapshots = snapshots;
    this.actors = actors;
    tx = new TransactionTemplate(manager);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    tx.setTimeout(30);
  }

  public UUID submit(String username, String key, UUID dataset, LocalDate date) {
    if (key == null || !key.matches("[A-Za-z0-9_-]{1,128}"))
      throw ImportFault.of(
          400,
          "INVALID_IDEMPOTENCY_KEY",
          0,
          "Idempotency-Key",
          "Use 1-128 letters, digits, underscores or hyphens");
    var actor = actors.writer(username);
    String hash = EventFingerprint.sha((dataset + "|" + date).getBytes(StandardCharsets.UTF_8));
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        return tx.execute(
            t -> {
              var old =
                  jdbc.queryForList(
                      "SELECT body_hash,run_id FROM idempotency_request WHERE actor_id=? AND endpoint='/api/reconciliation-runs' AND key=?",
                      actor.id(),
                      key);
              if (!old.isEmpty()) {
                if (!hash.equals(old.getFirst().get("body_hash")))
                  throw ImportFault.of(
                      409,
                      "IDEMPOTENCY_CONFLICT",
                      0,
                      "Idempotency-Key",
                      "Key already used with different request parameters");
                return (UUID) old.getFirst().get("run_id");
              }
              UUID id = snapshots.freeze(dataset, date);
              jdbc.update(
                  "INSERT INTO idempotency_request(actor_id,endpoint,key,body_hash,run_id) VALUES (?,'/api/reconciliation-runs',?,?,?)",
                  actor.id(),
                  key,
                  hash,
                  id);
              return id;
            });
      } catch (DuplicateKeyException | CannotSerializeTransactionException race) {
        if (attempt == 2)
          throw ImportFault.of(
              409,
              "RUN_ACTIVE",
              0,
              "businessDate",
              "An active run exists; retry the original request with its original key");
      }
    }
    throw new IllegalStateException("Unreachable");
  }
}
