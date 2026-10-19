package dev.huanling.traderecon.ai;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class AiEvidence {
  private final ObjectMapper json;
  private final EvidenceBuilder builder;
  private final TemplateInvestigator templates;

  public AiEvidence(ObjectMapper j, EvidenceBuilder b, TemplateInvestigator t) {
    json = j;
    builder = b;
    templates = t;
  }

  public ObjectNode create(UUID caseId, UUID resultId, String hash, JsonNode raw) {
    var evidence = builder.build(caseId, resultId, hash, raw);
    ObjectNode n = json.valueToTree(evidence);
    var books = n.putArray("runbooks");
    for (var step : templates.investigate(evidence).nextChecks())
      books.addObject().put("id", step.runbook()).put("instruction", step.instruction());
    boolean insufficient =
        evidence.contextIncomplete()
            || evidence.facts().stream()
                .anyMatch(
                    f ->
                        f.field().equals("projection")
                            && !Set.of("ACTIVE", "CANCELLED").contains(f.value()));
    n.put("insufficientEvidence", insufficient);
    // Reserve space for runbooks in the same 8000-character input contract.
    var facts = (ArrayNode) n.get("facts");
    while (n.toString().length() > 8000 && !facts.isEmpty()) {
      facts.remove(facts.size() - 1);
      n.put("contextIncomplete", true);
      n.put("insufficientEvidence", true);
    }
    return n;
  }

  public void timeline(ObjectNode input, List<Map<String, Object>> events) {
    var facts = (ArrayNode) input.path("facts");
    int total = input.path("totalFacts").asInt() + events.size();
    int index = 0;
    for (var event : events) {
      var fact =
          json.createObjectNode()
              .put("id", "T" + (++index))
              .put("source", event.get("source").toString())
              .put("field", "revision_timeline")
              .put(
                  "value",
                  "Revision "
                      + event.get("revision")
                      + "; occurred "
                      + event.get("occurred_at")
                      + "; received "
                      + event.get("received_at"));
      facts.add(fact);
      if (facts.size() > 40 || input.toString().length() > 7900) {
        facts.remove(facts.size() - 1);
        input.put("contextIncomplete", true);
        input.put("insufficientEvidence", true);
        break;
      }
    }
    input.put("totalFacts", total);
  }
}
