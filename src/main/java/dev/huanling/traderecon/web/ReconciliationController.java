package dev.huanling.traderecon.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.huanling.traderecon.ai.*;
import dev.huanling.traderecon.jobs.*;
import dev.huanling.traderecon.persistence.ReconciliationQueries;
import dev.huanling.traderecon.security.DemoActors;
import java.security.Principal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ReconciliationController {
  private final RunSubmissionService service;
  private final LeaseService leases;
  private final DemoActors actors;
  private final EvidenceBuilder evidenceBuilder;
  private final TemplateInvestigator templates;
  private final ReconciliationQueries queries;

  public ReconciliationController(
      RunSubmissionService service,
      ReconciliationQueries queries,
      LeaseService leases,
      DemoActors actors,
      EvidenceBuilder evidenceBuilder,
      TemplateInvestigator templates) {
    this.service = service;
    this.queries = queries;
    this.leases = leases;
    this.actors = actors;
    this.evidenceBuilder = evidenceBuilder;
    this.templates = templates;
  }

  @PostMapping({"/runs", "/reconciliation-runs"})
  public ResponseEntity<?> start(
      @RequestParam UUID dataset,
      @RequestParam("business_date") LocalDate date,
      @RequestHeader("Idempotency-Key") String key,
      Principal principal) {
    UUID id = service.submit(principal.getName(), key, dataset, date);
    return ResponseEntity.accepted()
        .body(Map.of("id", id, "runId", id, "status", queries.run(id, 0, 1).get("status")));
  }

  @PostMapping({"/reconciliation-runs/{id}/retry", "/runs/{id}/retry"})
  public ResponseEntity<?> retry(@PathVariable UUID id, Principal principal) {
    actors.writer(principal.getName());
    leases.retry(id, principal.getName());
    return ResponseEntity.accepted()
        .body(Map.of("id", id, "runId", id, "status", queries.run(id, 0, 1).get("status")));
  }

  @GetMapping({"/runs", "/reconciliation-runs"})
  public Object runs(
      @RequestParam UUID dataset,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    ImportController.paging(page, size);
    return queries.runs(dataset, page, size);
  }

  @GetMapping({"/runs/{id}", "/reconciliation-runs/{id}"})
  public Object run(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    ImportController.paging(page, size);
    return queries.run(id, page, size);
  }

  @GetMapping("/cases")
  public Object cases(
      @RequestParam UUID dataset,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    ImportController.paging(page, size);
    return queries.cases(dataset, page, size);
  }

  @GetMapping("/cases/{id}")
  public Object caseDetail(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    ImportController.paging(page, size);
    return queries.caseDetail(id, page, size);
  }

  @GetMapping("/cases/{id}/investigation")
  public Object investigation(@PathVariable UUID id) {
    var item = queries.caseDetail(id, 0, 1);
    var evidence =
        evidenceBuilder.build(
            id,
            (UUID) item.get("current_result_id"),
            item.get("evidence_hash").toString(),
            (JsonNode) item.get("evidence_json"));
    return Map.of("evidence", evidence, "report", templates.investigate(evidence), "ai", "OFF");
  }
}
