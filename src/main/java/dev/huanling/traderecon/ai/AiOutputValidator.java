package dev.huanling.traderecon.ai;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class AiOutputValidator {
  private final ObjectMapper json =
      new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  public JsonNode validate(String raw, JsonNode input) {
    try {
      if (raw == null || raw.length() > 16000) throw new IllegalArgumentException();
      JsonNode n = json.readTree(raw);
      exact(n, "summary", "fact_refs", "hypotheses", "next_checks", "missing_evidence", "abstain");
      text(n.get("summary"), 1200);
      Set<String> facts = new HashSet<>(), books = new HashSet<>();
      input.path("facts").forEach(f -> facts.add(f.path("id").asText()));
      input.path("runbooks").forEach(f -> books.add(f.path("id").asText()));
      refs(n.get("fact_refs"), facts, true);
      array(n.get("hypotheses"), 5);
      for (var h : n.get("hypotheses")) {
        exact(h, "statement", "supporting_fact_ids", "uncertainty");
        text(h.get("statement"), 800);
        text(h.get("uncertainty"), 500);
        refs(h.get("supporting_fact_ids"), facts, true);
      }
      array(n.get("next_checks"), 8);
      for (var c : n.get("next_checks")) {
        exact(c, "instruction", "fact_refs", "runbook_id");
        text(c.get("instruction"), 800);
        refs(c.get("fact_refs"), facts, true);
        if (!books.contains(c.path("runbook_id").asText())) throw new IllegalArgumentException();
      }
      array(n.get("missing_evidence"), 8);
      n.get("missing_evidence").forEach(x -> text(x, 500));
      if (!n.get("abstain").isBoolean()) throw new IllegalArgumentException();
      if (input.path("insufficientEvidence").asBoolean()
          && (!n.get("abstain").asBoolean()
              || n.get("missing_evidence").isEmpty()
              || !n.get("hypotheses").isEmpty())) throw new IllegalArgumentException();
      return n;
    } catch (Exception e) {
      throw new IllegalArgumentException("INVALID_AI_OUTPUT");
    }
  }

  private void exact(JsonNode n, String... fields) {
    if (n == null || !n.isObject()) throw new IllegalArgumentException();
    Set<String> actual = new HashSet<>();
    n.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(Set.of(fields))) throw new IllegalArgumentException();
  }

  private void text(JsonNode n, int max) {
    if (n == null || !n.isTextual() || n.asText().isBlank() || n.asText().length() > max)
      throw new IllegalArgumentException();
  }

  private void array(JsonNode n, int max) {
    if (n == null || !n.isArray() || n.size() > max) throw new IllegalArgumentException();
  }

  private void refs(JsonNode n, Set<String> allowed, boolean required) {
    array(n, 40);
    if (required && n.isEmpty()) throw new IllegalArgumentException();
    for (var x : n)
      if (!x.isTextual() || !allowed.contains(x.asText())) throw new IllegalArgumentException();
  }
}
