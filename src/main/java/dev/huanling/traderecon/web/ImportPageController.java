package dev.huanling.traderecon.web;

import dev.huanling.traderecon.domain.Source;
import dev.huanling.traderecon.ingestion.*;
import dev.huanling.traderecon.persistence.ImportRepository;
import dev.huanling.traderecon.persistence.InvestigationQueries;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Controller
public class ImportPageController {
  public static final UUID DEMO = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private final ImportService service;
  private final ImportRepository repo;
  private final InvestigationQueries queries;

  public ImportPageController(
      ImportService service, ImportRepository repo, InvestigationQueries queries) {
    this.service = service;
    this.repo = repo;
    this.queries = queries;
  }

  @GetMapping("/imports")
  public String page(
      Model model,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "00000000-0000-0000-0000-000000000001") UUID dataset,
      @RequestParam(required = false) LocalDate date,
      @RequestParam(required = false) Source source,
      @RequestParam(required = false) String status) {
    ImportController.paging(page, 50);
    model.addAttribute("listing", queries.batches(dataset, date, source, status, page, 50));
    model.addAttribute("dataset", dataset);
    model.addAttribute("date", date);
    model.addAttribute("source", source);
    model.addAttribute("status", status);
    model.addAttribute("page", page);
    return "imports";
  }

  @PostMapping("/imports")
  public String upload(
      Model model,
      HttpServletResponse response,
      @RequestParam(defaultValue = "00000000-0000-0000-0000-000000000001") UUID dataset,
      @RequestParam Source source,
      @RequestParam("business_date") LocalDate date,
      @RequestParam(defaultValue = "false", name = "feed_complete") boolean complete,
      @RequestParam MultipartFile file)
      throws java.io.IOException {
    try {
      if (file.getOriginalFilename() == null
          || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".csv"))
        throw ImportFault.of(400, "INVALID_FILE_TYPE", 0, "file", "Use a .csv file");
      var r = service.ingest(dataset, source, date, complete, file.getBytes());
      model.addAttribute("result", r);
    } catch (ImportFault e) {
      response.setStatus(e.status);
      model.addAttribute("problem", e);
    }
    return page(model, 0, dataset, date, null, null);
  }

  @PostMapping("/datasets")
  public String dataset(@RequestParam String name) {
    return "redirect:/imports?dataset=" + queries.createDataset(name);
  }

  @GetMapping("/imports/{id}")
  public String details(
      Model model, @PathVariable UUID id, @RequestParam(defaultValue = "0") int page) {
    ImportController.paging(page, 50);
    model.addAttribute("batch", repo.get(id));
    model.addAttribute("rows", repo.rows(id, page, 50));
    model.addAttribute("page", page);
    return "batch";
  }
}
