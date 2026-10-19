package dev.huanling.traderecon.persistence;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.huanling.traderecon.domain.Source;
import dev.huanling.traderecon.ingestion.*;
import dev.huanling.traderecon.reconciliation.ExecutionProjector;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ImportIT {
  @DynamicPropertySource
  static void db(DynamicPropertyRegistry r) {
    DatabaseIT.database(r);
  }

  @Autowired ImportService service;
  @Autowired ImportRepository repo;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  UUID d;
  final LocalDate date = LocalDate.of(2026, 10, 15);
  static final String HEADER = String.join(",", CsvParser.HEADER) + "\n";

  static String row(String id, String execution, int rev, String type, String price) {
    return id
        + ","
        + execution
        + ","
        + rev
        + ","
        + type
        + ",ACC01,STOCK01,BUY,USD,50,"
        + price
        + ",2026-10-15,2026-10-15T09:00:00Z,synthetic\n";
  }

  static byte[] file(String rows) {
    return (HEADER + rows).getBytes(StandardCharsets.UTF_8);
  }

  @BeforeEach
  void dataset() {
    d = UUID.randomUUID();
    jdbc.update("INSERT INTO dataset(id,name,kind) VALUES (?,?,'DEMO')", d, "test-" + d);
  }

  ImportService.Outcome ingest(byte[] b) {
    return service.ingest(d, Source.INTERNAL, date, false, b);
  }

  int count() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM trade_event WHERE dataset_id=?", Integer.class, d);
  }

  @Test
  void repeatedFileReturnsOriginalAndPreservesRawAndReceipt() {
    byte[] b = file(row("m1", "E1", 1, "NEW", "100.25"));
    var a = ingest(b);
    var first = repo.events(d, Source.INTERNAL, List.of("E1")).getFirst().receivedAt();
    for (int i = 0; i < 3; i++) {
      var again = ingest(b);
      assertTrue(again.repeatedFile());
      assertEquals(a.batch().id(), again.batch().id());
    }
    assertEquals(1, count());
    assertArrayEquals(
        b,
        jdbc.queryForObject(
            "SELECT raw_csv FROM import_batch WHERE id=?", byte[].class, a.batch().id()));
    assertEquals(first, repo.events(d, Source.INTERNAL, List.of("E1")).getFirst().receivedAt());
  }

  @Test
  void semanticAliasIsRememberedAgainstFutureIdentityConflict() {
    ingest(file(row("m1", "E1", 1, "NEW", "100")));
    var dup = ingest(file(row("m2", "E1", 1, "NEW", "100")));
    assertEquals(1, dup.batch().counts().get("duplicates").asInt());
    assertEquals(1, count());
    var fault =
        assertThrows(ImportFault.class, () -> ingest(file(row("m2", "E2", 1, "NEW", "100"))));
    assertEquals(409, fault.status);
    assertEquals("REJECTED", repo.get(fault.batchId).status());
  }

  @Test
  void sameBatchConflictRollsBackAllRowsAndRetainsDiagnostics() {
    byte[] b = file(row("m1", "E1", 1, "NEW", "100") + row("m1", "E1", 1, "NEW", "101"));
    var f = assertThrows(ImportFault.class, () -> ingest(b));
    assertEquals(0, count());
    assertEquals(2, repo.rows(f.batchId, 0, 50).size());
    assertEquals(
        "event_id", repo.get(f.batchId).error().get("fieldErrors").get(0).get("field").asText());
    assertArrayEquals(
        b,
        jdbc.queryForObject(
            "SELECT raw_csv FROM import_batch WHERE id=?", byte[].class, f.batchId));
  }

  @Test
  void conflictAgainstPriorBatchDoesNotCommitEarlierValidRow() {
    ingest(file(row("m1", "E1", 1, "NEW", "100")));
    assertThrows(
        ImportFault.class,
        () -> ingest(file(row("m2", "E2", 1, "NEW", "100") + row("m1", "E1", 1, "NEW", "101"))));
    assertEquals(1, count());
  }

  @Test
  void invalidReferenceAndPrecisionRejectWholeFile() {
    for (String bad :
        List.of(
            row("m1", "E1", 1, "NEW", "1.0000001"),
            row("m1", "E1", 1, "NEW", "100").replace("ACC01", "UNKNOWN"))) {
      var f =
          assertThrows(
              ImportFault.class, () -> ingest(file(row("good", "GOOD", 1, "NEW", "100") + bad)));
      assertEquals(422, f.status);
      assertEquals(0, count());
      assertEquals("REJECTED", repo.get(f.batchId).status());
    }
  }

  @Test
  void versionGapThenRepairAndCancellation() {
    ingest(file(row("m3", "E1", 3, "CORRECT", "102")));
    assertEquals("DATA_BLOCKED", project().status());
    ingest(file(row("m1", "E1", 1, "NEW", "100") + row("m2", "E1", 2, "CORRECT", "101")));
    assertEquals("ACTIVE", project().status());
    assertEquals(3, project().current().revision());
    ingest(file(row("m4", "E1", 4, "CANCEL", "102")));
    assertEquals("CANCELLED", project().status());
    ingest(file(row("m5", "E1", 5, "CORRECT", "102")));
    assertEquals("DATA_BLOCKED", project().status());
  }

  ExecutionProjector.Projection project() {
    return new ExecutionProjector()
        .project(
            repo.events(d, Source.INTERNAL, List.of("E1")).stream()
                .map(ImportRepository.Stored::event)
                .toList());
  }

  @Test
  void metadataAndDateMigrationAreRejected() {
    var b = file(row("m1", "E1", 1, "NEW", "100"));
    ingest(b);
    assertEquals(
        "BATCH_METADATA_CONFLICT",
        assertThrows(ImportFault.class, () -> service.ingest(d, Source.INTERNAL, date, true, b))
            .code);
    var changed = file(row("m2", "E1", 2, "CORRECT", "101").replace("2026-10-15,", "2026-10-16,"));
    assertEquals(
        409,
        assertThrows(
                ImportFault.class,
                () -> service.ingest(d, Source.INTERNAL, date.plusDays(1), false, changed))
            .status);
  }

  @Test
  void concurrentSameFileThreeTimesIsAcceptedOnce() throws Exception {
    var b = file(row("m1", "E1", 1, "NEW", "100"));
    try (var executor = Executors.newFixedThreadPool(3)) {
      var gate = new CountDownLatch(1);
      List<Future<ImportService.Outcome>> futures = new ArrayList<>();
      for (int i = 0; i < 3; i++)
        futures.add(
            executor.submit(
                () -> {
                  gate.await();
                  return ingest(b);
                }));
      gate.countDown();
      Set<UUID> ids = new HashSet<>();
      int fresh = 0;
      for (var f : futures) {
        var o = f.get(20, TimeUnit.SECONDS);
        ids.add(o.batch().id());
        if (!o.repeatedFile()) fresh++;
      }
      assertEquals(1, ids.size());
      assertEquals(1, fresh);
      assertEquals(1, count());
    }
  }

  @Test
  void concurrentDifferentFilesSameEventDoNotDuplicate() throws Exception {
    try (var executor = Executors.newFixedThreadPool(2)) {
      var a = file(row("m1", "E1", 1, "NEW", "100"));
      var b = file(row("m1", "E1", 1, "NEW", "100").replace("synthetic", "another note"));
      var gate = new CountDownLatch(1);
      var f1 =
          executor.submit(
              () -> {
                gate.await();
                return ingest(a);
              });
      var f2 =
          executor.submit(
              () -> {
                gate.await();
                return ingest(b);
              });
      gate.countDown();
      f1.get(20, TimeUnit.SECONDS);
      f2.get(20, TimeUnit.SECONDS);
      assertEquals(1, count());
      assertEquals(2, repo.list(d, 0, 50).size());
    }
  }

  @Test
  void apiRoleCsrfAndStatuses() throws Exception {
    var f =
        new MockMultipartFile(
            "file", "trades.csv", "text/csv", file(row("m1", "E1", 1, "NEW", "100")));
    mvc.perform(get("/api/imports").param("dataset", d.toString()))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            multipart("/api/imports")
                .file(f)
                .param("dataset", d.toString())
                .param("source", "INTERNAL")
                .param("business_date", date.toString())
                .param("feed_complete", "false")
                .with(user("viewer").roles("VIEWER"))
                .with(csrf()))
        .andExpect(status().isForbidden());
    mvc.perform(multipart("/api/imports").file(f).with(user("analyst").roles("ANALYST")))
        .andExpect(status().isForbidden());
    for (int status : List.of(201, 200))
      mvc.perform(
              multipart("/api/imports")
                  .file(f)
                  .param("dataset", d.toString())
                  .param("source", "INTERNAL")
                  .param("business_date", date.toString())
                  .param("feed_complete", "false")
                  .with(user("analyst").roles("ANALYST"))
                  .with(csrf()))
          .andExpect(status().is(status));
    mvc.perform(
            get("/api/imports")
                .param("dataset", d.toString())
                .param("size", "201")
                .with(user("viewer")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.traceId").exists());
    mvc.perform(
            get("/api/executions/E1")
                .param("dataset", d.toString())
                .param("source", "INTERNAL")
                .with(user("viewer")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.projection.current.money.price").value("100.000000"));
  }

  @Test
  void importPageRendersAndEscapesNotes() throws Exception {
    var f =
        new MockMultipartFile(
            "file",
            "trades.csv",
            "text/csv",
            file(
                row("m" + d, "E" + d, 1, "NEW", "100")
                    .replace("synthetic", "<script>alert(1)</script>")));
    mvc.perform(
            multipart("/imports")
                .file(f)
                .param("source", "INTERNAL")
                .param("business_date", date.toString())
                .with(user("analyst").roles("ANALYST"))
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(view().name("imports"))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Batch accepted")));
  }

  @Test
  void fileValidationAndErrorEnvelope() throws Exception {
    var f =
        new MockMultipartFile(
            "file", "trades.csv", "text/csv", file(row("m1", "E1", 1, "NEW", "-1")));
    mvc.perform(
            multipart("/api/imports")
                .file(f)
                .param("dataset", d.toString())
                .param("source", "INTERNAL")
                .param("business_date", date.toString())
                .param("feed_complete", "false")
                .with(user("analyst").roles("ANALYST"))
                .with(csrf()))
        .andExpect(status().is(422))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("price"))
        .andExpect(jsonPath("$.batchId").exists());
  }

  @Test
  void sourceIsPartOfIdentity() {
    var b = file(row("m1", "E1", 1, "NEW", "100"));
    ingest(b);
    service.ingest(d, Source.BROKER, date, false, b);
    assertEquals(2, count());
  }
}
