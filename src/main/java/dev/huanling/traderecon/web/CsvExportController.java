package dev.huanling.traderecon.web;

import dev.huanling.traderecon.ingestion.ImportFault;
import dev.huanling.traderecon.persistence.ReconciliationQueries;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
public class CsvExportController {
  private final ReconciliationQueries queries;

  public CsvExportController(ReconciliationQueries queries) {
    this.queries = queries;
  }

  public static String cell(Object value) {
    String s = String.valueOf(value == null ? "" : value);
    if (s.stripLeading().matches("(?s)^[=+@-].*")
        || (!s.isEmpty() && (s.charAt(0) == '\t' || s.charAt(0) == '\r' || s.charAt(0) == '\n')))
      s = "'" + s;
    return "\"" + s.replace("\"", "\"\"") + "\"";
  }

  @GetMapping({"/api/reconciliation-runs/{id}/export", "/api/runs/{id}/export"})
  public ResponseEntity<StreamingResponseBody> export(@PathVariable UUID id) {
    var first = queries.run(id, 0, 200);
    if (!"SUCCEEDED".equals(first.get("status")))
      throw ImportFault.of(
          409, "RUN_NOT_COMPLETE", 0, "id", "Wait for successful publication before exporting");
    StreamingResponseBody body =
        out -> {
          out.write(
              "execution_id,status,reason_codes,evidence_hash,snapshot_time,rules_version\r\n"
                  .getBytes(StandardCharsets.UTF_8));
          long total = ((Number) first.get("total")).longValue();
          for (int page = 0; (long) page * 200 < total; page++) {
            var run = page == 0 ? first : queries.run(id, page, 200);
            @SuppressWarnings("unchecked")
            var rows = (List<Map<String, Object>>) run.get("results");
            for (var row : rows) {
              String line =
                  String.join(
                          ",",
                          cell(row.get("execution_id")),
                          cell(row.get("status")),
                          cell(row.get("reason_codes")),
                          cell(row.get("evidence_hash")),
                          cell(run.get("as_of")),
                          cell(run.get("rules_version")))
                      + "\r\n";
              out.write(line.getBytes(StandardCharsets.UTF_8));
            }
          }
        };
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename("reconciliation-" + id + ".csv")
                .build()
                .toString())
        .body(body);
  }
}
