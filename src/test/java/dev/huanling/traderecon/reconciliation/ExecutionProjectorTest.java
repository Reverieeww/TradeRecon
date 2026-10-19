package dev.huanling.traderecon.reconciliation;

import static org.junit.jupiter.api.Assertions.*;

import dev.huanling.traderecon.domain.*;
import dev.huanling.traderecon.domain.Currency;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ExecutionProjectorTest {
  final ExecutionProjector p = new ExecutionProjector();
  final ExecutionKey key = new ExecutionKey(UUID.randomUUID(), LocalDate.of(2026, 10, 15), "E1");

  TradeEvent e(int rev, EventType type, String price) {
    return new TradeEvent(
        key,
        Source.INTERNAL,
        "ev" + rev,
        rev,
        type,
        "ACC01",
        "STOCK01",
        Side.BUY,
        new MoneyFields(BigDecimal.ONE, new BigDecimal(price), Currency.USD),
        Instant.EPOCH,
        "");
  }

  @Test
  void shuffledContinuousChainUsesLatest() {
    var a = e(1, EventType.NEW, "100");
    var b = e(2, EventType.CORRECT, "101");
    var c = e(3, EventType.CORRECT, "102");
    assertEquals(c, p.project(List.of(c, a, b)).current());
  }

  @Test
  void missingNewAndGapBlocked() {
    assertEquals(
        List.of("MISSING_NEW"), p.project(List.of(e(3, EventType.CORRECT, "100"))).reasons());
    assertEquals(
        List.of("VERSION_GAP"),
        p.project(List.of(e(1, EventType.NEW, "100"), e(3, EventType.CORRECT, "101"))).reasons());
  }

  @Test
  void gapRepairRestoresValidState() {
    assertEquals(
        "ACTIVE",
        p.project(
                List.of(
                    e(1, EventType.NEW, "100"),
                    e(3, EventType.CORRECT, "102"),
                    e(2, EventType.CORRECT, "101")))
            .status());
  }

  @Test
  void cancelMustRetainBusinessFields() {
    assertEquals(
        "CANCELLED",
        p.project(List.of(e(1, EventType.NEW, "100"), e(2, EventType.CANCEL, "100"))).status());
    assertEquals(
        List.of("INVALID_CANCEL_PAYLOAD"),
        p.project(List.of(e(1, EventType.NEW, "100"), e(2, EventType.CANCEL, "101"))).reasons());
  }

  @Test
  void resurrectionIsBlocked() {
    assertEquals(
        List.of("AFTER_CANCEL"),
        p.project(
                List.of(
                    e(1, EventType.NEW, "100"),
                    e(2, EventType.CANCEL, "100"),
                    e(3, EventType.CORRECT, "100")))
            .reasons());
  }

  @Test
  void duplicatesDoNotChangeResult() {
    var a = e(1, EventType.NEW, "100");
    assertEquals(p.project(List.of(a)), p.project(List.of(a, a)));
  }

  @Test
  void randomizedOrderingIsStable() {
    var base =
        List.of(
            e(1, EventType.NEW, "100"),
            e(2, EventType.CORRECT, "101"),
            e(3, EventType.CANCEL, "101"));
    var random = new Random(101);
    for (int i = 0; i < 100; i++) {
      var copy = new ArrayList<>(base);
      Collections.shuffle(copy, random);
      assertEquals(p.project(base), p.project(copy));
    }
  }
}
