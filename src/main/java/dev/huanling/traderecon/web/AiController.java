package dev.huanling.traderecon.web;

import dev.huanling.traderecon.ai.AiService;
import java.security.Principal;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class AiController {
  private final AiService service;

  public AiController(AiService service) {
    this.service = service;
  }

  @PostMapping("/api/cases/{id}/ai-analysis")
  public ResponseEntity<AiService.Submission> submit(
      @PathVariable UUID id,
      @RequestParam UUID resultId,
      @RequestParam String evidenceHash,
      Principal principal) {
    var s = service.submit(principal.getName(), id, resultId, evidenceHash);
    return ResponseEntity.status(s.cached() || s.status().equals("FAILED") ? 200 : 202).body(s);
  }

  @GetMapping("/api/ai-analyses/{id}")
  public Map<String, Object> get(@PathVariable UUID id) {
    return service.get(id);
  }
}
