package dev.huanling.traderecon.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.huanling.traderecon.domain.Source;
import dev.huanling.traderecon.ingestion.*;
import dev.huanling.traderecon.reconciliation.ReconciliationService;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

/** Explicit benchmark only; one warm-up then ten measurements per TOTAL two-feed event size. */
@SpringBootTest
class ReleaseBenchmark {
  @DynamicPropertySource
  static void db(DynamicPropertyRegistry r) {
    DatabaseIT.database(r);
  }

  @Autowired ImportService imports;
  @Autowired ReconciliationService recon;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;

  @Test
  void measureImportAndReconciliationSeparately() throws Exception {
    var samples = new ArrayList<Map<String, Object>>();
    var failures = new ArrayList<Map<String, Object>>();
    Map<String, Object> report = new LinkedHashMap<>();
    report.put(
        "scope",
        "Service ingestion and full snapshot/calculation/transactional publication, separately; excludes HTTP, source generation, dataset setup, verification queries and AI.");
    report.put(
        "totalRowsMeaning",
        "Sum of BOTH feeds; unique execution keys=totalRows/2; 1% price discrepancies, zero duplicate rows.");
    report.put("warmups", 1);
    report.put("measuredRepeats", 10);
    report.put("seed", "sequential deterministic IDs; no RNG");
    report.put("java", System.getProperty("java.version"));
    report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
    report.put("cpu", System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "unknown"));
    report.put("logicalProcessors", Runtime.getRuntime().availableProcessors());
    report.put("jvmMaxHeap", Runtime.getRuntime().maxMemory());
    report.put("postgres", jdbc.queryForObject("SELECT version()", String.class));
    report.put("baseCommit", "fb17b29 plus recorded source hash");
    report.put("sourceSha256", sourceHash());
    report.put("recordedAt", Instant.now().toString());
    report.put("samples", samples);
    report.put("failures", failures);
    Path path = Path.of("evidence/day7-benchmark.json");
    Files.createDirectories(path.getParent());
    for (int total : List.of(1000, 10000, 100000)) {
      String expected = null;
      boolean sizeFailed = false;
      for (int iteration = -1; iteration < 10; iteration++) {
        UUID dataset = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO dataset(id,name,kind) VALUES (?,?,'BENCH')",
            dataset,
            "release-bench-" + dataset);
        List<byte[]> internal = csv(total / 2, false), broker = csv(total / 2, true);
        ManagementFactory.getMemoryPoolMXBeans().forEach(p -> p.resetPeakUsage());
        long start = System.nanoTime();
        double importMs = 0, reconMs = 0;
        try {
          for (var bytes : internal)
            imports.ingest(dataset, Source.INTERNAL, LocalDate.of(2026, 10, 18), true, bytes);
          for (var bytes : broker)
            imports.ingest(dataset, Source.BROKER, LocalDate.of(2026, 10, 18), true, bytes);
          importMs = (System.nanoTime() - start) / 1e6;
          long calculation = System.nanoTime();
          UUID run = recon.run(dataset, LocalDate.of(2026, 10, 18));
          reconMs = (System.nanoTime() - calculation) / 1e6;
          assertEquals(
              total,
              jdbc.queryForObject(
                  "SELECT count(*) FROM trade_event WHERE dataset_id=?", Integer.class, dataset));
          assertEquals(
              total / 2,
              jdbc.queryForObject(
                  "SELECT count(*) FROM recon_result WHERE run_id=?", Integer.class, run));
          assertEquals(
              total / 200,
              jdbc.queryForObject(
                  "SELECT count(*) FROM recon_result WHERE run_id=? AND status='BREAK'",
                  Integer.class,
                  run));
          // Dataset UUID differs per repeat: normalize only that identity in stored business
          // evidence.
          String digest =
              jdbc.queryForObject(
                  "SELECT md5(string_agg(execution_id||':'||status||':'||replace(evidence_json::text,?, 'DATASET'),',' ORDER BY execution_id)) FROM recon_result WHERE run_id=?",
                  String.class,
                  dataset.toString(),
                  run);
          if (expected == null) expected = digest;
          else assertEquals(expected, digest, "Business output changed between repetitions");
          long heap =
              ManagementFactory.getMemoryPoolMXBeans().stream()
                  .filter(p -> p.getType() == java.lang.management.MemoryType.HEAP)
                  .mapToLong(p -> p.getPeakUsage().getUsed())
                  .sum();
          if (iteration >= 0) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("totalRows", total);
            s.put("repeat", iteration + 1);
            s.put("importMs", importMs);
            s.put("reconciliationMs", reconMs);
            s.put("sumOfHeapPoolPeaksBytes", heap);
            s.put("dataset", dataset.toString());
            s.put("businessDigest", digest);
            s.put("batchCount", internal.size() + broker.size());
            samples.add(s);
          }
          System.out.printf(
              Locale.ROOT,
              "RELEASE_BENCH total=%d repeat=%d importMs=%.3f reconMs=%.3f verified=true%n",
              total,
              iteration,
              importMs,
              reconMs);
        } catch (Exception | AssertionError e) {
          failures.add(
              Map.of(
                  "totalRows",
                  total,
                  "iteration",
                  iteration,
                  "exception",
                  e.getClass().getSimpleName(),
                  "dataset",
                  dataset.toString(),
                  "elapsedMs",
                  (System.nanoTime() - start) / 1e6));
          sizeFailed = true;
          System.out.println("BENCH_FAILED total=" + total + " " + e.getClass().getSimpleName());
        }
        json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), report);
        if (sizeFailed) break;
      }
      if (sizeFailed && total <= 10000)
        fail("Required <=10k scale failed; see evidence/day7-benchmark.json");
    }
  }

  List<byte[]> csv(int keys, boolean broker) {
    var batches = new ArrayList<byte[]>();
    for (int from = 0; from < keys; from += 10000) {
      var s = new StringBuilder(String.join(",", CsvParser.HEADER)).append('\n');
      for (int i = from; i < Math.min(keys, from + 10000); i++)
        s.append("ev")
            .append(i)
            .append(",E")
            .append(i)
            .append(",1,NEW,ACC01,STOCK01,BUY,USD,50,")
            .append(broker && i % 100 == 0 ? "101" : "100")
            .append(",2026-10-18,2026-10-18T09:00:00Z,synthetic performance\n");
      batches.add(s.toString().getBytes(StandardCharsets.UTF_8));
    }
    return batches;
  }

  String sourceHash() throws Exception {
    try (var paths = Files.walk(Path.of("src"))) {
      var s = new StringBuilder();
      for (var p : paths.filter(Files::isRegularFile).sorted().toList())
        s.append(p.toString().replace('\\', '/'))
            .append('\n')
            .append(Files.readString(p).replace("\r\n", "\n"));
      return EventFingerprint.sha(s.toString().getBytes(StandardCharsets.UTF_8));
    }
  }
}
