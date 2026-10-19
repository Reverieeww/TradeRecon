package dev.huanling.traderecon.ai;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class EvidenceBuilder {
  public record Fact(String id, String source, String field, String value) {}

  public record Evidence(
      UUID caseId,
      UUID resultId,
      String evidenceHash,
      String rulesVersion,
      List<Fact> facts,
      int totalFacts,
      boolean contextIncomplete) {}

  public record Field(String name, String internal, String broker, boolean different) {}

  private final ObjectMapper json;

  public EvidenceBuilder(ObjectMapper json) {
    this.json = json;
  }

  public Evidence build(UUID caseId, UUID resultId, String hash, JsonNode evidence) {
    var facts = new ArrayList<Fact>();
    add(facts, "RULE", "status", evidence.path("status").asText());
    evidence.path("reasonCodes").forEach(n -> add(facts, "RULE", "reason", n.asText()));
    for (String source : List.of("INTERNAL", "BROKER")) {
      add(facts, source, "projection", evidence.path(source + "_projection").asText("ABSENT"));
      var current = evidence.path(source + "_current");
      for (String field :
          List.of(
              "revision", "type", "account", "instrument", "side", "currency", "quantity", "price"))
        if (current.has(field)) add(facts, source, field, current.path(field).asText());
      evidence.path(source + "_versions").forEach(n -> add(facts, source, "version", n.asText()));
    }
    int total = facts.size();
    var kept = new ArrayList<>(facts.subList(0, Math.min(40, total)));
    Evidence result;
    do {
      result =
          new Evidence(
              caseId,
              resultId,
              hash,
              evidence.path("rulesVersion").asText(),
              List.copyOf(kept),
              total,
              kept.size() != total);
      if (json.valueToTree(result).toString().length() <= 8000) return result;
      if (kept.isEmpty()) throw new IllegalArgumentException("Evidence metadata exceeds limit");
      kept.removeLast();
    } while (true);
  }

  private void add(List<Fact> facts, String source, String field, String value) {
    facts.add(new Fact("F" + (facts.size() + 1), source, field, value));
  }

  public List<Field> fields(JsonNode evidence) {
    var result = new ArrayList<Field>();
    for (String field :
        List.of("account", "instrument", "side", "currency", "quantity", "price", "cancellation")) {
      String a = value(evidence.path("INTERNAL_current"), field),
          b = value(evidence.path("BROKER_current"), field);
      result.add(
          new Field(
              field, a, b, !a.equals("Unavailable") && !b.equals("Unavailable") && !a.equals(b)));
    }
    return result;
  }

  private String value(JsonNode current, String field) {
    if (current.isMissingNode()) return "Unavailable";
    return field.equals("cancellation")
        ? (current.path("type").asText().equals("CANCEL") ? "CANCELLED" : "ACTIVE")
        : current.path(field).asText("Unavailable");
  }
}
