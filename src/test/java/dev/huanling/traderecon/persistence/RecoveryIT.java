package dev.huanling.traderecon.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.huanling.traderecon.cases.DecisionService;
import dev.huanling.traderecon.cases.DecisionService.Action;
import dev.huanling.traderecon.domain.*;
import dev.huanling.traderecon.ingestion.*;
import dev.huanling.traderecon.jobs.*;
import dev.huanling.traderecon.reconciliation.*;
import dev.huanling.traderecon.security.DemoActors;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest
@AutoConfigureMockMvc
class RecoveryIT {
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"import", "calculation", "publication", "after-commit"})
  void actualChildProcessKillAtFourBoundaries(String phase) throws Exception {
    if (!phase.equals("import")) {
      upload(Source.INTERNAL, 1, "10");
      upload(Source.BROKER, 1, "11");
    }
    String key = "kill-" + phase;
    UUID id = submit(key);
    var frozen = snapshots.load(id).input();
    var log = java.nio.file.Path.of("evidence", "day4-process-" + phase + ".log");
    String javaExecutable =
        java.nio.file.Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
            .toString();
    var builder =
        new ProcessBuilder(
                javaExecutable,
                "-cp",
                System.getProperty(
                    "surefire.test.class.path", System.getProperty("java.class.path")),
                CrashWorkerProbe.class.getName(),
                phase,
                id.toString(),
                dataset.toString())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile());
    var env = builder.environment();
    env.put("SPRING_DATASOURCE_URL", DatabaseIT.URL);
    env.put("SPRING_DATASOURCE_USERNAME", "traderecon_app");
    env.put(
        "SPRING_DATASOURCE_PASSWORD",
        System.getenv().getOrDefault("TEST_APP_PASSWORD", "integration-only-password"));
    env.put("SPRING_FLYWAY_USER", DatabaseIT.USER);
    env.put("SPRING_FLYWAY_PASSWORD", DatabaseIT.PASSWORD);
    env.put("APP_DEMO_PASSWORD", "Test-only-password-123");
    Process child = builder.start();
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
      boolean ready = false;
      while (System.nanoTime() < deadline && child.isAlive()) {
        if (java.nio.file.Files.exists(log)
            && java.nio.file.Files.readString(log).contains("READY_TO_KILL")) {
          ready = true;
          break;
        }
        Thread.sleep(100);
      }
      assertTrue(ready, "Child did not reach fault boundary; see " + log);
    } finally {
      child.destroyForcibly();
      assertTrue(child.waitFor(15, TimeUnit.SECONDS));
    }
    if (phase.equals("import")) {
      assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT count(*) FROM trade_event WHERE dataset_id=?", Integer.class, dataset));
      assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT count(*) FROM import_batch WHERE dataset_id=?", Integer.class, dataset));
      imports.ingest(dataset, Source.INTERNAL, date, true, CrashWorkerProbe.csv());
      imports.ingest(dataset, Source.INTERNAL, date, true, CrashWorkerProbe.csv());
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM trade_event WHERE dataset_id=?", Integer.class, dataset));
      service.execute(id);
    } else if (phase.equals("after-commit")) {
      assertEquals("SUCCEEDED", queries.run(id, 0, 50).get("status"));
      long audit = count("audit_event", id);
      assertEquals(id, submit(key));
      leases.retry(id, "analyst");
      assertEquals(audit, count("audit_event", id));
      assertEquals(1, count("recon_result", id));
    } else {
      assertEquals(0, count("recon_result", id));
      assertTrue(queries.cases(dataset, 0, 50).isEmpty());
      clock.value.set(Instant.now().plusSeconds(61));
      // Re-created lease service represents a new worker; no production sleep or clock endpoint.
      var restarted = new LeaseService(jdbc, repo, clock, manager);
      service.execute(restarted.claim(id));
      assertEquals("SUCCEEDED", queries.run(id, 0, 50).get("status"));
      assertEquals(1, count("recon_result", id));
      assertEquals(
          new DefaultReconciliationEngine().reconcile(frozen).getFirst().evidenceHash(),
          jdbc.queryForObject(
              "SELECT evidence_hash FROM recon_result WHERE run_id=?", String.class, id));
    }
    java.nio.file.Files.writeString(
        log,
        "\nPASS forced child termination at "
            + phase
            + "; database invariants and recovery verified. run="
            + id
            + "\n",
        java.nio.file.StandardOpenOption.APPEND);
  }

  @Test
  void unavailableConnectionDoesNotLoseQueuedJob() {
    UUID id = submit("unavailable-db");
    var broken =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            "jdbc:postgresql://127.0.0.1:1/traderecon_test?connectTimeout=1", "unused", "unused");
    var badLease =
        new LeaseService(
            new JdbcTemplate(broken),
            repo,
            clock,
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(broken));
    var worker = new RunWorker(badLease, service);
    try {
      worker.poll();
    } finally {
      worker.stop();
    }
    assertEquals("QUEUED", queries.run(id, 0, 50).get("status"));
    service.execute(id);
    assertEquals("SUCCEEDED", queries.run(id, 0, 50).get("status"));
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    DatabaseIT.database(r);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired ImportRepository repo;
  @Autowired CsvParser parser;
  @Autowired ObjectMapper json;
  @Autowired PlatformTransactionManager manager;
  @Autowired DemoActors actors;
  @Autowired ReconciliationQueries queries;
  @Autowired MockMvc mvc;
  UUID dataset;
  final LocalDate date = LocalDate.of(2026, 10, 18);
  MutableClock clock;
  ImportService imports;
  SnapshotService snapshots;
  LeaseService leases;
  ReconciliationService service;
  RunSubmissionService submissions;
  DecisionService decisions;

  static class MutableClock extends Clock {
    final AtomicReference<Instant> value =
        new AtomicReference<>(Instant.parse("2026-10-18T09:00:00Z"));

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return value.get();
    }

    void advance(long seconds) {
      value.updateAndGet(t -> t.plusSeconds(seconds));
    }
  }

  @BeforeEach
  void setup() {
    dataset = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO dataset(id,name,kind) VALUES (?,?,'DEMO')", dataset, "day4-" + dataset);
    clock = new MutableClock();
    imports = new ImportService(repo, parser, clock, manager);
    snapshots = new SnapshotService(jdbc, repo, json, clock, manager);
    leases = new LeaseService(jdbc, repo, clock, manager);
    service =
        new ReconciliationService(
            snapshots, new CaseService(jdbc, repo, clock), jdbc, repo, clock, manager);
    submissions = new RunSubmissionService(jdbc, snapshots, actors, manager);
    decisions = new DecisionService(jdbc, actors, repo, clock, manager);
  }

  void upload(Source source, int revision, String price) {
    String csv =
        "event_id,execution_id,revision,event_type,account_id,instrument_id,side,currency,quantity,price,trade_date,occurred_at,note\nV"
            + revision
            + ",E1,"
            + revision
            + ","
            + (revision == 1 ? "NEW" : "CORRECT")
            + ",ACC01,STOCK01,BUY,USD,100,"
            + price
            + ",2026-10-18,2026-10-18T09:00:00Z,\n";
    imports.ingest(dataset, source, date, true, csv.getBytes(StandardCharsets.UTF_8));
  }

  UUID submit(String key) {
    return submissions.submit("analyst", dataset + "-" + key, dataset, date);
  }

  UUID discrepancy() {
    upload(Source.INTERNAL, 1, "10");
    upload(Source.BROKER, 1, "11");
    UUID id = service.run(dataset, date);
    return (UUID) queries.cases(dataset, 0, 50).getFirst().get("id");
  }

  Map<String, Object> detail(UUID id) {
    return queries.caseDetail(id, 0, 50);
  }

  Object decision(String user, UUID id, Map<String, Object> proof, Action action) {
    return decisions.decide(
        user,
        id,
        ((Number) proof.get("version")).longValue(),
        (UUID) proof.get("current_result_id"),
        proof.get("evidence_hash").toString(),
        action,
        "Checked synthetic evidence");
  }

  long count(String table, UUID id) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE run_id=?", Long.class, id);
  }

  @Test
  void duplicateSubmissionAndLostResponseReturnOriginalCompletedRun() {
    upload(Source.INTERNAL, 1, "10");
    UUID id = submit("same-request");
    assertEquals(id, submit("same-request"));
    assertEquals(
        "IDEMPOTENCY_CONFLICT",
        assertThrows(
                ImportFault.class,
                () ->
                    submissions.submit(
                        "analyst", dataset + "-same-request", dataset, date.plusDays(1)))
            .code);
    service.execute(id);
    long audit = count("audit_event", id);
    assertEquals(id, submit("same-request"));
    assertEquals(id, leases.retry(id, "analyst"));
    assertEquals(audit, count("audit_event", id));
    assertEquals(1, count("recon_result", id));
  }

  @Test
  void simultaneousIdenticalRequestsCreateOneSnapshotAndOneKey() throws Exception {
    try (var executor = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      Callable<UUID> submitter =
          () -> {
            gate.await();
            return submit("concurrent");
          };
      var a = executor.submit(submitter);
      var b = executor.submit(submitter);
      gate.countDown();
      UUID id = a.get(15, TimeUnit.SECONDS);
      assertEquals(id, b.get(15, TimeUnit.SECONDS));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM recon_run WHERE dataset_id=?", Integer.class, dataset));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM idempotency_request WHERE run_id=?", Integer.class, id));
      service.execute(id);
    }
  }

  @Test
  void crashBeforePublicationRotatesTokenAndRejectsZombieWorker() {
    upload(Source.INTERNAL, 1, "10");
    UUID id = submit("crash-before-publish");
    var first = leases.claim(id);
    var frozen = snapshots.load(id).input();
    var baseline = new DefaultReconciliationEngine().reconcile(frozen);
    clock.advance(61);
    var recovered = leases.claim(id);
    assertNotEquals(first.token(), recovered.token());
    assertFalse(leases.heartbeat(first));
    assertEquals(
        "STALE_OWNER",
        assertThrows(ImportFault.class, () -> service.publish(first, baseline)).code);
    leases.fail(first);
    assertEquals("RUNNING", queries.run(id, 0, 50).get("status"));
    upload(Source.BROKER, 1, "10");
    service.execute(recovered);
    assertEquals(frozen, snapshots.load(id).input());
    assertEquals(
        baseline.getFirst().evidenceHash(),
        jdbc.queryForObject(
            "SELECT evidence_hash FROM recon_result WHERE run_id=?", String.class, id));
    assertEquals(
        "WAITING",
        jdbc.queryForObject("SELECT status FROM recon_result WHERE run_id=?", String.class, id));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM audit_event WHERE run_id=? AND action='RUN_RECOVERED'",
            Integer.class,
            id));
  }

  @Test
  void heartbeatKeepsLeaseAliveButExpiredWorkerCannotResurrectIt() {
    UUID id = submit("heartbeat");
    var lease = leases.claim(id);
    clock.advance(50);
    assertTrue(leases.heartbeat(lease));
    clock.advance(20);
    assertEquals("RUN_NOT_CLAIMABLE", assertThrows(ImportFault.class, () -> leases.claim(id)).code);
    clock.advance(41);
    assertFalse(leases.heartbeat(lease));
    assertEquals(
        "STALE_OWNER",
        assertThrows(ImportFault.class, () -> service.publish(lease, List.of())).code);
    service.execute(leases.claim(id));
  }

  @Test
  void failedPublicationRollsBackCasesThenRetryUsesSameFrozenInput() {
    upload(Source.INTERNAL, 1, "10");
    upload(Source.BROKER, 1, "11");
    UUID id = submit("publish-failure");
    var brokenCases =
        new CaseService(jdbc, repo, clock) {
          @Override
          public void apply(UUID run, UUID result, ReconciliationEngine.Result r) {
            super.apply(run, result, r);
            throw new IllegalStateException("injected mid-publication crash");
          }
        };
    var failing = new ReconciliationService(snapshots, brokenCases, jdbc, repo, clock, manager);
    assertThrows(IllegalStateException.class, () -> failing.execute(leases.claim(id)));
    assertEquals("FAILED", queries.run(id, 0, 50).get("status"));
    assertEquals(0, count("recon_result", id));
    assertTrue(queries.cases(dataset, 0, 50).isEmpty());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM audit_event WHERE run_id=? AND case_id IS NOT NULL",
            Integer.class,
            id));
    upload(Source.BROKER, 2, "10");
    var before = snapshots.load(id).input();
    leases.retry(id, "analyst");
    service.execute(id);
    assertEquals(before, snapshots.load(id).input());
    assertEquals(
        "BREAK",
        jdbc.queryForObject("SELECT status FROM recon_result WHERE run_id=?", String.class, id));
    UUID fresh = service.run(dataset, date);
    assertEquals(
        "MATCHED",
        jdbc.queryForObject("SELECT status FROM recon_result WHERE run_id=?", String.class, fresh));
  }

  @Test
  void activeRetryRejectedAndOldFailedSnapshotCannotRegressNewerCases() {
    UUID old = submit("old");
    var lease = leases.claim(old);
    assertEquals(
        "RUN_ACTIVE", assertThrows(ImportFault.class, () -> leases.retry(old, "analyst")).code);
    leases.fail(lease);
    service.run(dataset, date);
    assertEquals(
        "STALE_RUN", assertThrows(ImportFault.class, () -> leases.retry(old, "analyst")).code);
  }

  @Test
  void twoReviewersWithSameVersionProduceOneDecisionAndOneConflict() throws Exception {
    UUID id = discrepancy();
    var proof = detail(id);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      List<Future<String>> outcomes = new ArrayList<>();
      for (String user : List.of("analyst", "supervisor"))
        outcomes.add(
            executor.submit(
                () -> {
                  gate.await();
                  try {
                    decision(user, id, proof, Action.CLAIM);
                    return "OK";
                  } catch (ImportFault e) {
                    return e.code;
                  }
                }));
      gate.countDown();
      var actual = new ArrayList<String>();
      for (var future : outcomes) actual.add(future.get(15, TimeUnit.SECONDS));
      Collections.sort(actual);
      assertEquals(List.of("OK", "STALE_EVIDENCE"), actual);
      assertEquals(1L, detail(id).get("version"));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM audit_event WHERE case_id=? AND action='MANUAL_CLAIM'",
              Integer.class,
              id));
    }
  }

  @Test
  void permissionsTransitionsAndStaleEvidenceAreEnforced() {
    UUID id = discrepancy();
    var original = detail(id);
    assertEquals(
        "FORBIDDEN",
        assertThrows(ImportFault.class, () -> decision("viewer", id, original, Action.CLAIM)).code);
    assertEquals(
        "FORBIDDEN",
        assertThrows(ImportFault.class, () -> decision("analyst", id, original, Action.CLOSE))
            .code);
    assertEquals(
        "INVALID_TRANSITION",
        assertThrows(ImportFault.class, () -> decision("analyst", id, original, Action.ESCALATE))
            .code);
    decision("analyst", id, original, Action.CLAIM);
    decision("analyst", id, detail(id), Action.ESCALATE);
    decision("supervisor", id, detail(id), Action.CLOSE);
    assertEquals("CLOSED_WITH_REASON", detail(id).get("review_state"));
    assertEquals("BREAK", detail(id).get("status"));
    service.run(dataset, date);
    assertEquals("CLOSED_WITH_REASON", detail(id).get("review_state"));
    var stale = detail(id);
    upload(Source.BROKER, 2, "12");
    service.run(dataset, date);
    assertEquals("OPEN", detail(id).get("review_state"));
    assertEquals(
        "STALE_EVIDENCE",
        assertThrows(ImportFault.class, () -> decision("supervisor", id, stale, Action.CLOSE))
            .code);
    upload(Source.BROKER, 3, "10");
    service.run(dataset, date);
    assertEquals("RESOLVED_BY_DATA", detail(id).get("review_state"));
    assertEquals(
        "INVALID_TRANSITION",
        assertThrows(ImportFault.class, () -> decision("supervisor", id, detail(id), Action.CLOSE))
            .code);
  }

  @Test
  void sameVersionButWrongResultOrHashIsRejectedWithoutAudit() {
    UUID id = discrepancy();
    var proof = detail(id);
    long version = ((Number) proof.get("version")).longValue();
    UUID result = (UUID) proof.get("current_result_id");
    assertEquals(
        "STALE_EVIDENCE",
        assertThrows(
                ImportFault.class,
                () ->
                    decisions.decide(
                        "analyst",
                        id,
                        version,
                        UUID.randomUUID(),
                        proof.get("evidence_hash").toString(),
                        Action.CLAIM,
                        "reason"))
            .code);
    assertEquals(
        "STALE_EVIDENCE",
        assertThrows(
                ImportFault.class,
                () ->
                    decisions.decide(
                        "analyst", id, version, result, "0".repeat(64), Action.CLAIM, "reason"))
            .code);
    assertEquals(
        "INVALID_DECISION",
        assertThrows(
                ImportFault.class,
                () ->
                    decisions.decide(
                        "analyst",
                        id,
                        version,
                        result,
                        proof.get("evidence_hash").toString(),
                        Action.CLAIM,
                        " "))
            .code);
    assertEquals(1, ((List<?>) detail(id).get("audit")).size());
  }

  @Test
  void workerPollProcessesQueuedJob() {
    UUID id = submit("worker-poll");
    var worker = new RunWorker(leases, service);
    try {
      worker.poll();
    } finally {
      worker.stop();
    }
    assertEquals("SUCCEEDED", queries.run(id, 0, 50).get("status"));
  }

  @Test
  void httpContractsRequireKeyRoleCsrfAndVersion() throws Exception {
    UUID id = discrepancy();
    var c = detail(id);
    var request =
        post("/api/cases/" + id + "/claim")
            .with(user("analyst").roles("ANALYST"))
            .with(csrf())
            .param("expectedVersion", c.get("version").toString())
            .param("resultId", c.get("current_result_id").toString())
            .param("evidenceHash", c.get("evidence_hash").toString())
            .param("reason", "Reviewed synthetic evidence");
    mvc.perform(request)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reviewState").value("IN_REVIEW"));
    mvc.perform(request)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("STALE_EVIDENCE"));
    mvc.perform(
            post("/api/reconciliation-runs/" + UUID.randomUUID() + "/retry")
                .with(user("viewer").roles("VIEWER"))
                .with(csrf()))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/cases/" + id + "/decisions").with(user("supervisor").roles("SUPERVISOR")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/cases/" + id).with(user("viewer")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("expectedVersion")));
  }

  @Test
  void publicationReadsLatestManualClosureInsteadOfStaleCalculationState() {
    UUID id = discrepancy();
    UUID run = submit("close-during-calculation");
    var lease = leases.claim(run);
    var results = new DefaultReconciliationEngine().reconcile(snapshots.load(run).input());
    decision("supervisor", id, detail(id), Action.CLOSE);
    service.publish(lease, results);
    assertEquals("CLOSED_WITH_REASON", detail(id).get("review_state"));
    assertEquals(2, ((List<?>) detail(id).get("audit")).size());
  }

  @Test
  void reviewerWaitingForPublicationLockGetsStaleEvidenceConflict() throws Exception {
    UUID id = discrepancy();
    var old = detail(id);
    upload(Source.BROKER, 2, "12");
    UUID run = submit("review-publish-race");
    var lease = leases.claim(run);
    var calculated = new DefaultReconciliationEngine().reconcile(snapshots.load(run).input());
    var changed = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var started = new CountDownLatch(1);
    var blockingCases =
        new CaseService(jdbc, repo, clock) {
          @Override
          public void apply(UUID r, UUID result, ReconciliationEngine.Result value) {
            super.apply(r, result, value);
            changed.countDown();
            try {
              if (!release.await(10, TimeUnit.SECONDS))
                throw new IllegalStateException("Test coordination timeout");
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(e);
            }
          }
        };
    var publisher = new ReconciliationService(snapshots, blockingCases, jdbc, repo, clock, manager);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var publishing = executor.submit(() -> publisher.publish(lease, calculated));
      try {
        assertTrue(changed.await(10, TimeUnit.SECONDS));
        var reviewing =
            executor.submit(
                () -> {
                  started.countDown();
                  try {
                    decision("supervisor", id, old, Action.CLOSE);
                    return "OK";
                  } catch (ImportFault e) {
                    return e.code;
                  }
                });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        release.countDown();
        publishing.get(10, TimeUnit.SECONDS);
        assertEquals("STALE_EVIDENCE", reviewing.get(10, TimeUnit.SECONDS));
        assertEquals("OPEN", detail(id).get("review_state"));
      } finally {
        release.countDown();
      }
    }
  }
}
