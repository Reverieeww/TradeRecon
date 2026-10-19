package dev.huanling.traderecon.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.huanling.traderecon.ai.*;
import dev.huanling.traderecon.cases.DecisionService;
import dev.huanling.traderecon.cases.DecisionService.Action;
import dev.huanling.traderecon.domain.*;
import dev.huanling.traderecon.jobs.*;
import dev.huanling.traderecon.persistence.InvestigationQueries;
import dev.huanling.traderecon.persistence.ReconciliationQueries;
import dev.huanling.traderecon.security.DemoActors;
import java.security.Principal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class ReconciliationPageController {
  private final RunSubmissionService service;
  private final LeaseService leases;
  private final DecisionService decisions;
  private final DemoActors actors;
  private final ReconciliationQueries queries;
  private final InvestigationQueries views;
  private final EvidenceBuilder evidenceBuilder;
  private final TemplateInvestigator templates;
  private final AiService ai;

  public ReconciliationPageController(
      RunSubmissionService service,
      ReconciliationQueries queries,
      LeaseService leases,
      DecisionService decisions,
      DemoActors actors,
      InvestigationQueries views,
      EvidenceBuilder evidenceBuilder,
      TemplateInvestigator templates,
      AiService ai) {
    this.service = service;
    this.queries = queries;
    this.leases = leases;
    this.decisions = decisions;
    this.actors = actors;
    this.views = views;
    this.evidenceBuilder = evidenceBuilder;
    this.templates = templates;
    this.ai = ai;
  }

  @GetMapping("/runs")
  public String page(
      Model model,
      @RequestParam(required = false) UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "0") int historyPage,
      @RequestParam(defaultValue = "00000000-0000-0000-0000-000000000001") UUID dataset,
      @RequestParam(required = false) LocalDate date,
      @RequestParam(required = false) String status) {
    ImportController.paging(page, 50);
    ImportController.paging(historyPage, 50);
    if (id != null) {
      var run = queries.run(id, page, 50);
      model.addAttribute("run", run);
      dataset = (UUID) run.get("dataset_id");
    }
    model.addAttribute("listing", views.runs(dataset, date, status, historyPage, 50));
    model.addAttribute("dataset", dataset);
    model.addAttribute("date", date);
    model.addAttribute("status", status);
    model.addAttribute("page", page);
    model.addAttribute("requestKey", UUID.randomUUID().toString());
    return "runs";
  }

  @PostMapping("/runs")
  public String start(
      @RequestParam("business_date") LocalDate date,
      @RequestParam(defaultValue = "00000000-0000-0000-0000-000000000001") UUID dataset,
      @RequestParam String requestKey,
      Principal principal) {
    UUID id = service.submit(principal.getName(), requestKey, dataset, date);
    return "redirect:/runs?id=" + id;
  }

  @PostMapping("/runs/{id}/retry")
  public String retry(@PathVariable UUID id, Principal principal) {
    actors.writer(principal.getName());
    leases.retry(id, principal.getName());
    return "redirect:/runs?id=" + id;
  }

  @PostMapping("/cases/{id}/decisions")
  public String decide(
      @PathVariable UUID id,
      @RequestParam long expectedVersion,
      @RequestParam UUID resultId,
      @RequestParam String evidenceHash,
      @RequestParam Action action,
      @RequestParam String reason,
      Principal principal) {
    decisions.decide(
        principal.getName(), id, expectedVersion, resultId, evidenceHash, action, reason);
    return "redirect:/cases/" + id;
  }

  @PostMapping("/cases/{id}/ai-analysis")
  public String requestAi(
      @PathVariable UUID id,
      @RequestParam UUID resultId,
      @RequestParam String evidenceHash,
      Principal principal) {
    ai.submit(principal.getName(), id, resultId, evidenceHash);
    return "redirect:/cases/" + id + "#ai-analysis";
  }

  @GetMapping("/cases/{id}")
  public String casePage(
      Model model,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "0") int timelinePage) {
    ImportController.paging(page, 50);
    ImportController.paging(timelinePage, 50);
    var item = queries.caseDetail(id, page, 50);
    model.addAttribute("item", item);
    model.addAttribute("aiMode", ai.mode());
    model.addAttribute("analyses", ai.latest(id));
    model.addAttribute("dataset", item.get("dataset_id"));
    var evidence =
        evidenceBuilder.build(
            id,
            (UUID) item.get("current_result_id"),
            item.get("evidence_hash").toString(),
            (JsonNode) item.get("evidence_json"));
    model.addAttribute("facts", evidence);
    model.addAttribute("fields", evidenceBuilder.fields((JsonNode) item.get("evidence_json")));
    model.addAttribute("investigation", templates.investigate(evidence));
    model.addAttribute(
        "timeline",
        views.timeline(
            (UUID) item.get("run_id"), item.get("execution_id").toString(), timelinePage, 50));
    model.addAttribute("timelinePage", timelinePage);
    model.addAttribute("page", page);
    return "case";
  }

  @GetMapping("/cases")
  public String cases(
      Model model,
      @RequestParam(defaultValue = "00000000-0000-0000-0000-000000000001") UUID dataset,
      @RequestParam(required = false) LocalDate date,
      @RequestParam(required = false) ReviewState review,
      @RequestParam(required = false) ReconStatus status,
      @RequestParam(required = false) String execution,
      @RequestParam(defaultValue = "0") int page) {
    ImportController.paging(page, 50);
    model.addAttribute("listing", views.cases(dataset, date, review, status, execution, page, 50));
    model.addAttribute("dataset", dataset);
    model.addAttribute("date", date);
    model.addAttribute("review", review);
    model.addAttribute("status", status);
    model.addAttribute("execution", execution);
    return "cases";
  }
}
