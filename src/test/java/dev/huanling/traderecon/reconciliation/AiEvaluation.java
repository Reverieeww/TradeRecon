package dev.huanling.traderecon.reconciliation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import dev.huanling.traderecon.ai.*;
import dev.huanling.traderecon.domain.*;
import dev.huanling.traderecon.domain.Currency;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Explicit invocation only. All outputs retained, including failures; no model-generated grades.
 */
class AiEvaluation {
  final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

  @Test
  void evaluateFrozenCorpus() throws Exception {
    boolean live = Boolean.getBoolean("ai.eval.live");
    var env = new MockEnvironment().withProperty("app.ai-mode", live ? "LIVE" : "MOCK");
    var mapping =
        Map.of(
            "key",
            "OPENAI_API_KEY",
            "model",
            "AI_MODEL",
            "live-approved",
            "AI_LIVE_APPROVED",
            "budget-usd",
            "AI_BUDGET_USD",
            "input-usd-per-million",
            "AI_INPUT_USD_PER_MILLION",
            "output-usd-per-million",
            "AI_OUTPUT_USD_PER_MILLION",
            "max-requests",
            "AI_MAX_REQUESTS");
    mapping.forEach((k, v) -> env.setProperty("app.ai." + k, System.getenv().getOrDefault(v, "")));
    var settings = new AiSettings(env);
    if (live && settings.unavailable() != null)
      throw new IllegalStateException(settings.unavailable());
    InvestigationProvider provider =
        live
            ? new LiveInvestigationProvider(json, settings.key(), settings.model())
            : new MockInvestigationProvider(json);
    Path folder = Path.of("evidence", live ? "ai-live" : "ai-mock");
    Files.createDirectories(folder);
    String run = Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID();
    Path output = folder.resolve(run + ".jsonl");
    Files.createFile(output);
    // LIVE has an append-only cumulative reservation ledger shared across evaluation invocations.
    // A file lock prevents concurrent runs from each spending the same configured budget.
    Path ledger = folder.resolve("reservation-ledger.jsonl");
    try (var channel =
            java.nio.channels.FileChannel.open(
                ledger,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        var lock = channel.lock()) {
      var ledgerBytes = java.nio.ByteBuffer.allocate(Math.toIntExact(channel.size()));
      while (ledgerBytes.hasRemaining() && channel.read(ledgerBytes) >= 0) {}
      ledgerBytes.flip();
      String previous = StandardCharsets.UTF_8.decode(ledgerBytes).toString().strip();
      BigDecimal spent = BigDecimal.ZERO;
      for (String line : previous.lines().toList()) {
        BigDecimal next = new BigDecimal(json.readTree(line).path("reservedTotalUSD").asText());
        if (next.compareTo(spent) < 0)
          throw new IllegalStateException("Budget ledger is not monotonic");
        spent = next;
      }
      int calls = 0, validCount = 0, fallbackCount = 0;
      List<JsonNode> scenarios = new ArrayList<>();
      for (String f : List.of("cases.json", "challenges.json"))
        json.readTree(Path.of("eval", f).toFile()).forEach(scenarios::add);
      for (var scenario : scenarios) {
        var result = result(scenario);
        UUID c =
            UUID.nameUUIDFromBytes(scenario.path("id").asText().getBytes(StandardCharsets.UTF_8));
        JsonNode input =
            new AiEvidence(json, new EvidenceBuilder(json), new TemplateInvestigator())
                .create(c, c, result.evidenceHash(), json.valueToTree(result.evidence()));
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("caseId", scenario.path("id").asText());
        record.put("mode", live ? "LIVE" : "MOCK");
        record.put("humanReview", "PENDING");
        record.put("input", input);
        record.put("promptVersion", "investigation-v1");
        record.put("expected", scenario.path("expected"));
        record.put("contextIncomplete", input.path("contextIncomplete").asBoolean());
        String failure = null;
        long start = System.nanoTime();
        try {
          if (input.path("contextIncomplete").asBoolean())
            throw new IllegalStateException("CONTEXT_INCOMPLETE");
          if (live) {
            var p = (LiveInvestigationProvider) provider;
            var reservation =
                settings.reservation(
                    p.request(input).toString().getBytes(StandardCharsets.UTF_8).length);
            if (calls >= settings.maxRequests()
                || spent.add(reservation).compareTo(settings.budget()) > 0)
              throw new IllegalStateException("BUDGET_LIMIT");
            spent = spent.add(reservation);
            channel.position(channel.size());
            var entry =
                StandardCharsets.UTF_8.encode(
                    json.writeValueAsString(
                            Map.of(
                                "reservedTotalUSD",
                                spent.toPlainString(),
                                "at",
                                Instant.now().toString()))
                        + "\n");
            while (entry.hasRemaining()) channel.write(entry);
            channel.force(true);
          }
          calls++;
          var reply = provider.analyse(input, Duration.ofSeconds(20));
          record.put("rawResponse", reply.raw());
          record.put("requestId", reply.requestId());
          record.put("actualModel", reply.model());
          record.put("usage", reply.usage());
          record.put("attempts", reply.attempts());
          record.put("validatedOutput", new AiOutputValidator().validate(reply.raw(), input));
          validCount++;
        } catch (Exception e) {
          failure =
              e instanceof IllegalArgumentException
                  ? "INVALID_OUTPUT"
                  : e instanceof IllegalStateException ? e.getMessage() : "PROVIDER_UNAVAILABLE";
          fallbackCount++;
        }
        record.put("failure", failure);
        record.put("latencyMs", (System.nanoTime() - start) / 1000000);
        record.put("templateFallback", failure != null);
        record.put("semanticCorrectness", "NOT_REVIEWED");
        Files.writeString(
            output, json.writeValueAsString(record) + "\n", StandardOpenOption.APPEND);
      }
      var summary = new LinkedHashMap<String, Object>();
      summary.put("mode", live ? "LIVE" : "MOCK");
      summary.put("cases", scenarios.size());
      summary.put("calls", calls);
      summary.put("validContracts", validCount);
      summary.put("fallbacks", fallbackCount);
      summary.put("humanReview", "PENDING");
      summary.put("semanticAccuracy", "NOT_MEASURED");
      summary.put("investigationTimeBenefit", "NOT_MEASURED");
      summary.put("cumulativeReservedUSD", spent);
      summary.put("rawFile", output.getFileName().toString());
      json.writerWithDefaultPrettyPrinter()
          .writeValue(folder.resolve(run + "-summary.json").toFile(), summary);
      if (!live) {
        assertEquals(70, scenarios.size());
        assertEquals(70, validCount);
      }
    }
  }

  ReconciliationEngine.Result result(JsonNode scenario) {
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

    return result;
  }
}
