package dev.huanling.traderecon.ai;

import com.fasterxml.jackson.databind.*;
import java.time.Duration;

/** Deterministic test double. Never reported as model performance. */
public class MockInvestigationProvider implements InvestigationProvider {
  private final ObjectMapper json;

  public MockInvestigationProvider(ObjectMapper json) {
    this.json = json;
  }

  public Reply analyse(JsonNode input, Duration timeout) {
    var n = json.createObjectNode();
    n.put(
        "summary",
        "MOCK fixture: review the rule facts and source history. No root cause is established.");
    n.putArray("fact_refs").add(input.path("facts").get(0).path("id").asText());
    n.putArray("hypotheses");
    var checks = n.putArray("next_checks");
    for (var b : input.path("runbooks")) {
      var c =
          checks
              .addObject()
              .put("instruction", b.path("instruction").asText())
              .put("runbook_id", b.path("id").asText());
      c.putArray("fact_refs").add(input.path("facts").get(0).path("id").asText());
    }
    var missing = n.putArray("missing_evidence");
    boolean abstain = input.path("insufficientEvidence").asBoolean();
    if (abstain) missing.add("Complete, valid histories from both sources are required.");
    n.put("abstain", abstain);
    return new Reply(n.toString(), "mock-no-network", "mock-v1", json.createObjectNode(), 1);
  }
}
