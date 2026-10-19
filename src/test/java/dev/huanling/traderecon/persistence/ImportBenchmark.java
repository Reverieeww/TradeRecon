package dev.huanling.traderecon.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.huanling.traderecon.domain.Source;
import dev.huanling.traderecon.ingestion.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

/** Explicit invocation only: ./mvnw -Dit.test=ImportBenchmark verify. Not a CI timing gate. */
@SpringBootTest
class ImportBenchmark {
  @DynamicPropertySource
  static void db(DynamicPropertyRegistry r) {
    DatabaseIT.database(r);
  }

  @Autowired ImportService service;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;

  @Test
  void compareEquivalentTransactionalImports() throws Exception {
    List<Map<String, Object>> samples = new ArrayList<>();
    Map<String, String> referenceHashes = new HashMap<>();
    for (int n : List.of(1000, 10000)) {
      StringBuilder csv = new StringBuilder(String.join(",", CsvParser.HEADER)).append('\n');
      for (int i = 0; i < n; i++)
        csv.append("m")
            .append(i)
            .append(",E")
            .append(i)
            .append(
                ",1,NEW,ACC01,STOCK01,BUY,USD,50.000000,100.250000,2026-10-15,2026-10-15T09:00:00Z,synthetic benchmark\n");
      byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
      for (int repetition = -1; repetition < 10; repetition++) {
        // Alternate ordering to reduce systematic first/second-run bias. -1 is warm-up.
        for (boolean batched :
            (repetition % 2 == 0 ? List.of(true, false) : List.of(false, true))) {
          UUID dataset = UUID.randomUUID();
          jdbc.update(
              "INSERT INTO dataset(id,name,kind) VALUES (?,?,'BENCH')",
              dataset,
              "bench-" + dataset);
          long start = System.nanoTime();
          var result =
              service.ingest(
                  dataset, Source.INTERNAL, LocalDate.of(2026, 10, 15), false, bytes, batched);
          double milliseconds = (System.nanoTime() - start) / 1_000_000.0;
          assertEquals(n, result.batch().counts().path("inserted").asInt());
          assertEquals(
              n,
              jdbc.queryForObject(
                  "SELECT count(*) FROM trade_event WHERE dataset_id=?", Integer.class, dataset));
          assertEquals(
              n,
              jdbc.queryForObject(
                  "SELECT count(*) FROM event_identity WHERE dataset_id=?",
                  Integer.class,
                  dataset));
          assertEquals(
              n,
              jdbc.queryForObject(
                  "SELECT count(*) FROM import_row WHERE batch_id=?",
                  Integer.class,
                  result.batch().id()));
          String digest =
              jdbc.queryForObject(
                  "SELECT md5(string_agg(execution_id || ':' || revision || ':' || payload_hash, ',' ORDER BY execution_id,revision)) FROM trade_event WHERE dataset_id=?",
                  String.class,
                  dataset);
          String prior = referenceHashes.putIfAbsent(Integer.toString(n), digest);
          if (prior != null)
            assertEquals(prior, digest, "Baseline and batch must store identical business data");
          if (repetition >= 0)
            samples.add(
                Map.of(
                    "rows",
                    n,
                    "mode",
                    batched ? "batch" : "single",
                    "repeat",
                    repetition + 1,
                    "milliseconds",
                    milliseconds,
                    "dataset",
                    dataset,
                    "businessDigest",
                    digest,
                    "inputSha256",
                    EventFingerprint.sha(bytes)));
          System.out.printf(
              Locale.ROOT,
              "BENCH rows=%d mode=%s repetition=%d ms=%.3f verified=true%n",
              n,
              batched ? "batch" : "single",
              repetition,
              milliseconds);
        }
      }
    }
    List<Map<String, Object>> summaries = new ArrayList<>();
    for (int n : List.of(1000, 10000)) {
      Map<String, Double> medians = new HashMap<>();
      for (String mode : List.of("single", "batch")) {
        var times =
            samples.stream()
                .filter(s -> s.get("rows").equals(n) && s.get("mode").equals(mode))
                .mapToDouble(s -> (Double) s.get("milliseconds"))
                .sorted()
                .toArray();
        double median = (times[4] + times[5]) / 2;
        medians.put(mode, median);
        summaries.add(
            Map.of(
                "rows",
                n,
                "mode",
                mode,
                "repetitions",
                10,
                "medianMs",
                median,
                "minMs",
                times[0],
                "maxMs",
                times[9]));
      }
      System.out.printf(
          Locale.ROOT,
          "RESULT rows=%d single=%.3fms batch=%.3fms reduction=%.2f%%%n",
          n,
          medians.get("single"),
          medians.get("batch"),
          100 * (medians.get("single") - medians.get("batch")) / medians.get("single"));
    }
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("recordedAt", Instant.now().toString());
    report.put(
        "scope",
        "In-process CSV parse + validation + identity lookup + transactional writes + commit; excludes HTTP and file upload, dataset setup and verification queries");
    report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
    report.put("java", System.getProperty("java.version"));
    report.put("cpu", System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "unavailable"));
    report.put("logicalProcessors", Runtime.getRuntime().availableProcessors());
    report.put("jvmMaxMemoryBytes", Runtime.getRuntime().maxMemory());
    report.put("postgres", jdbc.queryForObject("SELECT version()", String.class));
    report.put("batchSize", 500);
    report.put("reWriteBatchedInserts", true);
    report.put("warmupsPerModePerSize", 1);
    report.put("duplicateFraction", 0);
    report.put("seed", "deterministic sequential IDs; no RNG");
    try (var paths = Files.walk(Path.of("src"))) {
      var source = new StringBuilder();
      for (var p : paths.filter(Files::isRegularFile).sorted().toList())
        source
            .append(p.toString().replace('\\', '/'))
            .append('\n')
            .append(Files.readString(p).replace("\r\n", "\n"));
      report.put(
          "sourceSha256", EventFingerprint.sha(source.toString().getBytes(StandardCharsets.UTF_8)));
    }
    report.put("samples", samples);
    report.put("summary", summaries);
    Files.createDirectories(Path.of("evidence"));
    json.writerWithDefaultPrettyPrinter()
        .writeValue(Path.of("evidence/day2-benchmark.json").toFile(), report);
  }
}
