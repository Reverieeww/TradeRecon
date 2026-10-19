package dev.huanling.traderecon.reconciliation;

import static org.junit.jupiter.api.Assertions.*;

import dev.huanling.traderecon.domain.*;
import dev.huanling.traderecon.domain.Currency;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ReconciliationEngineTest {
  final UUID dataset = UUID.randomUUID();
  final LocalDate date = LocalDate.of(2026, 10, 15);
  final Instant received = Instant.parse("2026-10-15T09:00:00Z");
  final ReconciliationEngine engine = new DefaultReconciliationEngine();

  TradeEvent event(Source source, int revision, EventType type, String price) {
    return new TradeEvent(
        new ExecutionKey(dataset, date, "E1"),
        source,
        "V" + revision,
        revision,
        type,
        "ACC01",
        "STOCK01",
        Side.BUY,
        new MoneyFields(new BigDecimal("100"), new BigDecimal(price), Currency.USD),
        received,
        "");
  }

  ReconciliationEngine.Input input(List<TradeEvent> events, long seconds, Set<Source> complete) {
    Map<ExecutionKey, Instant> times = new HashMap<>();
    events.forEach(e -> times.put(e.key(), received));
    return new ReconciliationEngine.Input(
        dataset, date, received.plusSeconds(seconds), RuleSet.VERSION, events, times, complete);
  }

  @ParameterizedTest
  @CsvSource({"599,WAITING", "600,BREAK", "601,BREAK"})
  void exactWaitBoundary(long seconds, ReconStatus expected) {
    assertEquals(
        expected,
        engine
            .reconcile(
                input(List.of(event(Source.INTERNAL, 1, EventType.NEW, "10")), seconds, Set.of()))
            .getFirst()
            .status());
  }

  @Test
  void bothCompleteOverridesWaitingButOneCompleteDoesNot() {
    var events = List.of(event(Source.INTERNAL, 1, EventType.NEW, "10"));
    assertEquals(
        ReconStatus.BREAK,
        engine
            .reconcile(input(events, 1, Set.of(Source.INTERNAL, Source.BROKER)))
            .getFirst()
            .status());
    assertEquals(
        ReconStatus.WAITING,
        engine.reconcile(input(events, 1, Set.of(Source.INTERNAL))).getFirst().status());
  }

  @Test
  void invalidVersionOutranksMissingAndTimeout() {
    var r =
        engine
            .reconcile(
                input(List.of(event(Source.INTERNAL, 3, EventType.CORRECT, "10")), 601, Set.of()))
            .getFirst();
    assertEquals(ReconStatus.DATA_BLOCKED, r.status());
    assertEquals(List.of("INTERNAL_MISSING_NEW"), r.reasons());
  }

  @Test
  void allDifferencesReportedAndCancellationCompared() {
    var a = event(Source.INTERNAL, 1, EventType.NEW, "10");
    var b =
        new TradeEvent(
            a.key(),
            Source.BROKER,
            "B1",
            1,
            EventType.NEW,
            "ACC02",
            "STOCK02",
            Side.SELL,
            new MoneyFields(new BigDecimal("200"), new BigDecimal("11"), Currency.GBP),
            received,
            "");
    var r = engine.reconcile(input(List.of(a, b), 1, Set.of())).getFirst();
    assertEquals(ReconStatus.BREAK, r.status());
    assertEquals(6, r.reasons().size());
    var cancel = event(Source.INTERNAL, 2, EventType.CANCEL, "10");
    var bSame = event(Source.BROKER, 1, EventType.NEW, "10");
    assertEquals(
        List.of("CANCELLATION_MISMATCH"),
        engine.reconcile(input(List.of(a, cancel, bSame), 1, Set.of())).getFirst().reasons());
    assertEquals(
        List.of("MATCHED_CANCELLED"),
        engine
            .reconcile(
                input(
                    List.of(a, cancel, bSame, event(Source.BROKER, 2, EventType.CANCEL, "10")),
                    1,
                    Set.of()))
            .getFirst()
            .reasons());
  }

  @Test
  void hundredPermutationsAndDuplicateCopiesKeepEvidenceHashAndUnionCount() {
    var values =
        List.of(
            event(Source.INTERNAL, 1, EventType.NEW, "10"),
            event(Source.INTERNAL, 2, EventType.CORRECT, "12"),
            event(Source.BROKER, 1, EventType.NEW, "12"));
    var expected = engine.reconcile(input(values, 1, Set.of()));
    var random = new Random(301);
    for (int i = 0; i < 100; i++) {
      var shuffled = new ArrayList<>(values);
      for (var value : values) if (random.nextBoolean()) shuffled.add(value);
      Collections.shuffle(shuffled, random);
      var actual = engine.reconcile(input(shuffled, 1 + i, Set.of()));
      assertEquals(expected, actual);
      assertEquals(shuffled.stream().map(TradeEvent::key).distinct().count(), actual.size());
    }
  }

  @Test
  void decimalNormalizationAndDeliveryMetadataDoNotChangeHash() {
    var a = event(Source.INTERNAL, 1, EventType.NEW, "10");
    var b = event(Source.BROKER, 1, EventType.NEW, "10.000000");
    var alias =
        new TradeEvent(
            a.key(),
            a.source(),
            "ALIAS",
            1,
            a.eventType(),
            a.accountId(),
            a.instrumentId(),
            a.side(),
            a.money(),
            received.plusSeconds(1),
            "irrelevant note");
    assertEquals(
        engine.reconcile(input(List.of(a, b), 1, Set.of())),
        engine.reconcile(input(List.of(alias, b), 2, Set.of())));
  }

  @Test
  void emptyInputAndUnsupportedRules() {
    assertTrue(engine.reconcile(input(List.of(), 1, Set.of())).isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            engine.reconcile(
                new ReconciliationEngine.Input(
                    dataset, date, received, "unknown", List.of(), Map.of(), Set.of())));
  }
}
