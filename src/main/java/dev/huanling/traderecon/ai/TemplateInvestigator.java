package dev.huanling.traderecon.ai;

import java.util.*;
import org.springframework.stereotype.Component;

/** Deterministic B-mode guidance, NOT model output or a proven root cause. */
@Component
public class TemplateInvestigator {
  public record Step(String runbook, String instruction, List<String> factRefs) {}

  public record Report(
      String mode, String summary, List<Step> nextChecks, boolean contextIncomplete) {}

  public Report investigate(EvidenceBuilder.Evidence evidence) {
    Map<String, List<String>> refs = new LinkedHashMap<>();
    for (var fact : evidence.facts())
      if (fact.field().equals("reason")) {
        String code = fact.value();
        String section =
            code.contains("MISSING_NEW")
                    || code.contains("VERSION_GAP")
                    || code.contains("REVISION_CONFLICT")
                    || code.contains("AFTER_CANCEL")
                    || code.contains("INVALID_CANCEL")
                ? "VERSIONS"
                : code.startsWith("MISSING_")
                        || code.contains("WAIT_WINDOW")
                        || code.equals("BOTH_FEEDS_COMPLETE")
                    ? "MISSING"
                    : code.contains("CANCELLATION") || code.equals("MATCHED_CANCELLED")
                        ? "CANCEL"
                        : code.equals("MATCHED_FIELDS") ? "MATCHED" : "FIELDS";
        refs.computeIfAbsent(section, k -> new ArrayList<>()).add(fact.id());
      }
    Map<String, String> instructions =
        Map.of(
            "VERSIONS",
                "Request the missing or conflicting revision history. Do not treat the largest revision as valid until the chain is complete.",
            "MISSING",
                "Check the source delivery log, completeness declaration and receipt deadline. Absence alone does not prove that the trade never occurred.",
            "CANCEL",
                "Check cancellation notices and the preceding valid versions from both sources. Do not infer a cancellation reason.",
            "MATCHED",
                "The compared business fields agree in this snapshot. This does not prove that either feed is complete.",
            "FIELDS",
                "Compare the highlighted fields with the original source records. Request a supported correction; do not guess which source is correct.");
    var steps =
        refs.entrySet().stream()
            .map(
                e ->
                    new Step(
                        "RB-" + e.getKey(),
                        instructions.get(e.getKey()),
                        List.copyOf(e.getValue())))
            .toList();
    return new Report(
        "TEMPLATE",
        "Rule-based investigation guidance. No root cause has been established. This template uses no AI.",
        steps,
        evidence.contextIncomplete());
  }
}
