package dev.huanling.traderecon.web;

import dev.huanling.traderecon.domain.Source;
import dev.huanling.traderecon.ingestion.*;
import dev.huanling.traderecon.persistence.ImportRepository;
import dev.huanling.traderecon.reconciliation.ExecutionProjector;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ImportController {
  private final ImportService service;
  private final ImportRepository repo;

  public ImportController(ImportService service, ImportRepository repo) {
    this.service = service;
    this.repo = repo;
  }

  @GetMapping("/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
  }

  @PostMapping("/imports")
  public ResponseEntity<?> upload(
      @RequestParam UUID dataset,
      @RequestParam Source source,
      @RequestParam("business_date") LocalDate date,
      @RequestParam("feed_complete") boolean complete,
      @RequestParam MultipartFile file)
      throws IOException {
    if (file.getOriginalFilename() == null
        || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".csv"))
      throw ImportFault.of(400, "INVALID_FILE_TYPE", 0, "file", "Use a .csv file");
    var outcome = service.ingest(dataset, source, date, complete, file.getBytes());
    return ResponseEntity.status(outcome.repeatedFile() ? 200 : 201).body(outcome);
  }

  @GetMapping("/imports")
  public Object list(
      @RequestParam UUID dataset,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    paging(page, size);
    return repo.list(dataset, page, size);
  }

  @GetMapping("/imports/{id}")
  public Object get(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    paging(page, size);
    return Map.of(
        "batch", repo.get(id), "rows", repo.rows(id, page, size), "page", page, "size", size);
  }

  @GetMapping("/executions/{executionId}")
  public Object projection(
      @PathVariable String executionId, @RequestParam UUID dataset, @RequestParam Source source) {
    dev.huanling.traderecon.domain.Identifiers.requireValid(executionId, "executionId");
    var events = repo.events(dataset, source, List.of(executionId));
    if (events.isEmpty())
      throw ImportFault.of(404, "NOT_FOUND", 0, "executionId", "Execution not found");
    return Map.of(
        "projection",
        new ExecutionProjector()
            .project(events.stream().map(ImportRepository.Stored::event).toList()),
        "firstReceivedAt",
        events.stream()
            .map(ImportRepository.Stored::receivedAt)
            .min(Comparator.naturalOrder())
            .orElseThrow(),
        "versions",
        events);
  }

  static void paging(int page, int size) {
    if (page < 0 || size < 1 || size > 200)
      throw ImportFault.of(400, "INVALID_PAGE", 0, "page", "page >= 0; size 1..200");
  }
}
