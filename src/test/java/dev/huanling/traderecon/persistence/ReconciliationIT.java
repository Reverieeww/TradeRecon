package dev.huanling.traderecon.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import dev.huanling.traderecon.domain.*;
import dev.huanling.traderecon.ingestion.*;
import dev.huanling.traderecon.reconciliation.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest
@AutoConfigureMockMvc
class ReconciliationIT {
  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    DatabaseIT.database(r);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired ImportRepository repo;
  @Autowired CsvParser parser;
  @Autowired ObjectMapper json;
  @Autowired PlatformTransactionManager manager;
  @Autowired ReconciliationQueries queries;
  @Autowired MockMvc mvc;
  UUID dataset;
  final LocalDate date = LocalDate.of(2026, 10, 15);
  final Instant at = Instant.parse("2026-10-15T09:00:00Z");
  ImportService imports;
  SnapshotService snapshots;
  ReconciliationService service;
  CaseService cases;
  Clock clock;

  @BeforeEach
  void setup() {
    dataset = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO dataset(id,name,kind) VALUES (?,?,'DEMO')", dataset, "day3-" + dataset);
    imports = new ImportService(repo, parser, Clock.fixed(at, ZoneOffset.UTC), manager);
    services(at.plusSeconds(60));
  }

  void services(Instant now) {
    clock = Clock.fixed(now, ZoneOffset.UTC);
    snapshots = new SnapshotService(jdbc, repo, json, clock, manager);
    cases = new CaseService(jdbc, repo, clock);
    service = new ReconciliationService(snapshots, cases, jdbc, repo, clock, manager);
  }

  byte[] csv(String execution, int revision, String price) {
    return ("event_id,execution_id,revision,event_type,account_id,instrument_id,side,currency,quantity,price,trade_date,occurred_at,note\n"
            + execution
            + "V"
            + revision
            + ","
            + execution
            + ","
            + revision
            + ","
            + (revision == 1 ? "NEW" : "CORRECT")
            + ",ACC01,STOCK01,BUY,USD,100,"
            + price
            + ",2026-10-15,2026-10-15T09:00:00Z,\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  void upload(Source source, String execution, int revision, String price, boolean complete) {
    imports.ingest(dataset, source, date, complete, csv(execution, revision, price));
  }

  String state(UUID id) {
    return jdbc.queryForObject("SELECT status FROM recon_result WHERE run_id=?", String.class, id);
  }

  static Stream<JsonNode> goldenCases() throws Exception {
    var list = new ArrayList<JsonNode>();
    new ObjectMapper()
        .readTree(Path.of("data/demo/manifest.json").toFile())
        .path("cases")
        .forEach(list::add);
    return list.stream();
  }

  @ParameterizedTest(name = "golden {index}")
  @MethodSource("goldenCases")
  void twentyGoldenCasesThroughRealImportSnapshotAndDatabase(JsonNode scenario) throws Exception {
    for (var batch : scenario.path("batches")) {
      try {
        imports.ingest(
            dataset,
            Source.valueOf(batch.path("source").asText()),
            date,
            batch.path("feed_complete").asBoolean(),
            Files.readAllBytes(Path.of("data/demo", batch.path("file").asText())));
      } catch (ImportFault e) {
        assertTrue(
            Set.of("04_identity_conflict", "18_excess_precision")
                .contains(scenario.path("id").asText()),
            scenario.path("id").asText() + ":" + e.code);
      }
    }
    services(Instant.parse(scenario.path("as_of").asText()));
    UUID id = service.run(dataset, date);
    Map<String, String> actual = new TreeMap<>();
    jdbc.queryForList("SELECT execution_id,status FROM recon_result WHERE run_id=?", id)
        .forEach(r -> actual.put(r.get("execution_id").toString(), r.get("status").toString()));
    Map<String, String> expected = new TreeMap<>();
    scenario
        .path("expected_status_by_execution")
        .fields()
        .forEachRemaining(e -> expected.put(e.getKey(), e.getValue().asText()));
    assertEquals(expected, actual, scenario.path("id").asText());
    assertEquals(
        snapshots.load(id).input().frozenEvents().stream().map(TradeEvent::key).distinct().count(),
        actual.size());
    assertEquals(
        new DefaultReconciliationEngine()
            .reconcile(snapshots.load(id).input()).stream()
                .map(ReconciliationEngine.Result::evidenceHash)
                .sorted()
                .toList(),
        jdbc.queryForList(
            "SELECT evidence_hash FROM recon_result WHERE run_id=? ORDER BY evidence_hash",
            String.class,
            id));
  }

  @Test
  void laterImportsCannotChangeOldSnapshotAndDuplicateBatchReferencesAreIncluded() {
    upload(Source.INTERNAL, "E1", 1, "10", false);
    var duplicate =
        new String(csv("E1", 1, "10"), StandardCharsets.UTF_8)
            .replace("E1V1", "E1ALIAS")
            .getBytes(StandardCharsets.UTF_8);
    imports.ingest(dataset, Source.INTERNAL, date, true, duplicate);
    UUID id = snapshots.create(dataset, date);
    var frozen = snapshots.load(id).input();
    assertEquals(1, frozen.frozenEvents().size());
    assertTrue(frozen.completeSources().contains(Source.INTERNAL));
    assertEquals(
        2, jdbc.queryForObject("SELECT count(*) FROM run_batch WHERE run_id=?", Integer.class, id));
    upload(Source.BROKER, "E1", 1, "10", true);
    service.execute(id);
    assertEquals("WAITING", state(id));
    assertEquals(frozen, snapshots.load(id).input());
    UUID fresh = service.run(dataset, date);
    assertEquals("MATCHED", state(fresh));
    assertEquals("COMPLETE", queries.run(fresh, 0, 50).get("completeness"));
  }

  @Test
  void stableCaseRerunReopenAndResolvePreserveHistory() {
    upload(Source.INTERNAL, "E1", 1, "10", true);
    upload(Source.BROKER, "E1", 1, "11", true);
    UUID first = service.run(dataset, date);
    var original = queries.cases(dataset, 0, 50).getFirst();
    UUID caseId = (UUID) original.get("id");
    service.run(dataset, date);
    assertEquals(1, queries.cases(dataset, 0, 50).size());
    assertEquals(0L, queries.caseDetail(caseId, 0, 50).get("version"));
    // Test-only preparation for day-4 reviewer behavior; no public manual endpoint exists yet.
    jdbc.update("UPDATE exception_case SET review_state='CLOSED_WITH_REASON' WHERE id=?", caseId);
    service.run(dataset, date);
    assertEquals("CLOSED_WITH_REASON", queries.caseDetail(caseId, 0, 50).get("review_state"));
    upload(Source.BROKER, "E1", 2, "12", true);
    service.run(dataset, date);
    assertEquals("OPEN", queries.caseDetail(caseId, 0, 50).get("review_state"));
    upload(Source.BROKER, "E1", 3, "10", true);
    service.run(dataset, date);
    var resolved = queries.caseDetail(caseId, 0, 50);
    assertEquals("RESOLVED_BY_DATA", resolved.get("review_state"));
    assertEquals(3, ((List<?>) resolved.get("audit")).size());
    assertEquals("BREAK", state(first));
    assertEquals(1, queries.cases(dataset, 0, 50).size());
  }

  @Test
  void provisionalRunMayContainMatchedAndRejectDiagnosticsAreFrozen() {
    upload(Source.INTERNAL, "E1", 1, "10", false);
    upload(Source.BROKER, "E1", 1, "10", false);
    UUID id = service.run(dataset, date);
    assertEquals("MATCHED", state(id));
    assertEquals("PROVISIONAL", queries.run(id, 0, 50).get("completeness"));
    upload(Source.INTERNAL, "E2", 1, "10", true);
    upload(Source.BROKER, "E2", 1, "10", true);
    UUID before = snapshots.create(dataset, date);
    assertThrows(ImportFault.class, () -> upload(Source.INTERNAL, "E2", 1, "99", true));
    service.execute(before);
    assertEquals("COMPLETE", queries.run(before, 0, 50).get("completeness"));
    assertEquals("PROVISIONAL", queries.run(service.run(dataset, date), 0, 50).get("completeness"));
  }

  @Test
  void publicationFailureRollsBackResultsCasesAndAudit() {
    upload(Source.INTERNAL, "E1", 1, "10", true);
    upload(Source.BROKER, "E1", 1, "11", true);
    var failingCases =
        new CaseService(jdbc, repo, clock) {
          @Override
          public void apply(UUID run, UUID result, ReconciliationEngine.Result value) {
            super.apply(run, result, value);
            throw new IllegalStateException("injected publication failure");
          }
        };
    var failing = new ReconciliationService(snapshots, failingCases, jdbc, repo, clock, manager);
    UUID id = snapshots.create(dataset, date);
    assertThrows(IllegalStateException.class, () -> failing.execute(id));
    assertEquals("FAILED", queries.run(id, 0, 50).get("status"));
    assertEquals(
        0,
        jdbc.queryForObject("SELECT count(*) FROM recon_result WHERE run_id=?", Integer.class, id));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM audit_event WHERE run_id=? AND case_id IS NOT NULL",
            Integer.class,
            id));
    assertTrue(queries.cases(dataset, 0, 50).isEmpty());
    assertEquals("BREAK", state(service.run(dataset, date)));
  }

  @Test
  void activeRunUniquenessAndSuccessfulExecutionAreIdempotent() {
    upload(Source.INTERNAL, "E1", 1, "10", false);
    UUID id = snapshots.create(dataset, date);
    assertEquals(
        "RUN_ACTIVE", assertThrows(ImportFault.class, () -> snapshots.create(dataset, date)).code);
    service.execute(id);
    service.execute(id);
    assertEquals(
        1,
        jdbc.queryForObject("SELECT count(*) FROM recon_result WHERE run_id=?", Integer.class, id));
  }

  @Test
  void apiPermissionsCsrfPagingAndPages() throws Exception {
    mvc.perform(
            post("/api/runs")
                .with(user("viewer").roles("VIEWER"))
                .with(csrf())
                .param("dataset", dataset.toString())
                .param("business_date", date.toString()))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/runs")
                .with(user("analyst").roles("ANALYST"))
                .param("dataset", dataset.toString())
                .param("business_date", date.toString()))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/runs")
                .with(user("analyst").roles("ANALYST"))
                .with(csrf())
                .param("dataset", dataset.toString())
                .param("business_date", date.toString()))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/runs")
                .with(user("analyst").roles("ANALYST"))
                .with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .param("dataset", dataset.toString())
                .param("business_date", date.toString()))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("QUEUED"));
    service.execute(
        jdbc.queryForObject(
            "SELECT id FROM recon_run WHERE dataset_id=? AND status='QUEUED'",
            UUID.class,
            dataset));
    mvc.perform(
            get("/api/runs")
                .with(user("viewer"))
                .param("dataset", dataset.toString())
                .param("size", "201"))
        .andExpect(status().isBadRequest());
    upload(Source.INTERNAL, "E1", 1, "10", true);
    upload(Source.BROKER, "E1", 1, "11", true);
    UUID id = service.run(dataset, date);
    UUID caseId = (UUID) queries.cases(dataset, 0, 50).getFirst().get("id");
    mvc.perform(get("/runs").with(user("viewer")).param("id", id.toString()))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("PRICE_MISMATCH")));
    mvc.perform(get("/cases/" + caseId).with(user("viewer")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("CASE_CREATED")));
  }
}
