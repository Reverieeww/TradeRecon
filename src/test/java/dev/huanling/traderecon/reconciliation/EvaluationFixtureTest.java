package dev.huanling.traderecon.reconciliation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import dev.huanling.traderecon.ai.*;
import dev.huanling.traderecon.domain.*;
import dev.huanling.traderecon.domain.Currency;
import dev.huanling.traderecon.ingestion.EventFingerprint;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class EvaluationFixtureTest {
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  static Stream<JsonNode> scenarios() throws Exception {
    var list = new ArrayList<JsonNode>();
    for (String file : List.of("cases.json", "challenges.json"))
      JSON.readTree(Path.of("eval", file).toFile()).forEach(list::add);
    return list.stream();
  }

  @ParameterizedTest(name = "frozen scenario {index}")
  @MethodSource("scenarios")
  void authoredLabelsMatchRulesAndTemplateReferencesAreValid(JsonNode scenario) {
    var date = LocalDate.of(2026, 10, 18);
    var start = Instant.parse("2026-10-18T09:00:00Z");
    var dataset = UUID.fromString("00000000-0000-0000-0000-000000000060");
    var key = new ExecutionKey(dataset, date, scenario.path("id").asText());
    var events = new ArrayList<TradeEvent>();
    for (var e : scenario.path("events"))
      events.add(
          new TradeEvent(
              key,
              Source.valueOf(e.path("source").asText()),
              "V" + e.path("revision").asInt(),
              e.path("revision").asInt(),
              EventType.valueOf(e.path("type").asText()),
              e.path("account").asText(),
              e.path("instrument").asText(),
              Side.valueOf(e.path("side").asText()),
              new MoneyFields(
                  new BigDecimal(e.path("quantity").asText()),
                  new BigDecimal(e.path("price").asText()),
                  Currency.valueOf(e.path("currency").asText())),
              start,
              e.path("note").asText()));
    var complete = EnumSet.noneOf(Source.class);
    scenario.path("completeSources").forEach(n -> complete.add(Source.valueOf(n.asText())));
    var result =
        new DefaultReconciliationEngine()
            .reconcile(
                new ReconciliationEngine.Input(
                    dataset,
                    date,
                    start.plusSeconds(scenario.path("elapsedSeconds").asLong()),
                    RuleSet.VERSION,
                    events,
                    Map.of(key, start),
                    complete))
            .getFirst();
    assertEquals(
        scenario.path("expected").path("status").asText(),
        result.status().name(),
        scenario.path("id").asText());
    var reasons = new ArrayList<String>();
    scenario.path("expected").path("reasons").forEach(n -> reasons.add(n.asText()));
    assertEquals(reasons, result.reasons(), scenario.path("id").asText());
    var evidence =
        new EvidenceBuilder(JSON)
            .build(
                UUID.randomUUID(),
                UUID.randomUUID(),
                result.evidenceHash(),
                JSON.valueToTree(result.evidence()));
    var report = new TemplateInvestigator().investigate(evidence);
    var sections = new ArrayList<String>();
    scenario.path("expected").path("runbooks").forEach(n -> sections.add(n.asText()));
    assertEquals(
        sections, report.nextChecks().stream().map(TemplateInvestigator.Step::runbook).toList());
    var ids = evidence.facts().stream().map(EvidenceBuilder.Fact::id).toList();
    for (var step : report.nextChecks()) {
      assertFalse(step.factRefs().isEmpty());
      assertTrue(ids.containsAll(step.factRefs()));
    }
    assertEquals("PENDING", scenario.path("humanReview").asText());
    assertEquals("TEMPLATE", report.mode());
  }

  @Test
  void frozenManifestHashesAndCountsRemainUnchanged() throws Exception {
    var manifest = JSON.readTree(Path.of("eval/manifest.json").toFile());
    var hashes = manifest.path("files").fields();
    while (hashes.hasNext()) {
      var f = hashes.next();
      assertEquals(
          f.getValue().asText(),
          EventFingerprint.sha(Files.readAllBytes(Path.of("eval", f.getKey()))));
    }
    assertEquals(60, JSON.readTree(Path.of("eval/cases.json").toFile()).size());
    assertEquals(10, JSON.readTree(Path.of("eval/challenges.json").toFile()).size());
    assertEquals("PENDING", manifest.path("humanReview").asText());
  }
}
