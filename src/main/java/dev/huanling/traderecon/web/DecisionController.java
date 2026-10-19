package dev.huanling.traderecon.web;

import dev.huanling.traderecon.cases.DecisionService;
import dev.huanling.traderecon.cases.DecisionService.Action;
import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cases")
public class DecisionController {
  private final DecisionService service;

  public DecisionController(DecisionService service) {
    this.service = service;
  }

  @PostMapping("/{id}/claim")
  public Object claim(
      @PathVariable UUID id,
      @RequestParam long expectedVersion,
      @RequestParam UUID resultId,
      @RequestParam String evidenceHash,
      @RequestParam String reason,
      Principal principal) {
    return service.decide(
        principal.getName(), id, expectedVersion, resultId, evidenceHash, Action.CLAIM, reason);
  }

  @PostMapping("/{id}/decisions")
  public Object decide(
      @PathVariable UUID id,
      @RequestParam long expectedVersion,
      @RequestParam UUID resultId,
      @RequestParam String evidenceHash,
      @RequestParam Action action,
      @RequestParam String reason,
      Principal principal) {
    return service.decide(
        principal.getName(), id, expectedVersion, resultId, evidenceHash, action, reason);
  }
}
