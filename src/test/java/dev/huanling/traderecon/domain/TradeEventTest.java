package dev.huanling.traderecon.domain;

import static org.junit.jupiter.api.Assertions.*;

import dev.huanling.traderecon.ingestion.BatchValidator;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TradeEventTest {
  private TradeEvent event(int revision, EventType type, String note) {
    return new TradeEvent(
        new ExecutionKey(UUID.randomUUID(), LocalDate.of(2026, 10, 15), "E100"),
        Source.INTERNAL,
        "ev001",
        revision,
        type,
        "ACC01",
        "STOCK01",
        Side.BUY,
        new MoneyFields(BigDecimal.ONE, BigDecimal.TEN, Currency.USD),
        Instant.EPOCH,
        note);
  }

  @Test
  void enforcesInitialEventType() {
    assertThrows(IllegalArgumentException.class, () -> event(1, EventType.CORRECT, ""));
    assertThrows(IllegalArgumentException.class, () -> event(2, EventType.NEW, ""));
    assertDoesNotThrow(() -> event(3, EventType.CORRECT, "")); // chain validation comes later
  }

  @Test
  void validatesNoteAndIdentifiers() {
    assertThrows(IllegalArgumentException.class, () -> event(1, EventType.NEW, "x".repeat(501)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ExecutionKey(UUID.randomUUID(), LocalDate.now(), "bad id"));
    assertEquals("", event(1, EventType.NEW, null).note());
  }

  @Test
  void validatesReferenceDataAndDate() {
    var validator = new BatchValidator(Set.of("ACC01"), Set.of("STOCK01"));
    assertDoesNotThrow(
        () -> validator.validate(event(1, EventType.NEW, ""), LocalDate.of(2026, 10, 15)));
    assertThrows(
        IllegalArgumentException.class,
        () -> validator.validate(event(1, EventType.NEW, ""), LocalDate.of(2026, 10, 16)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BatchValidator(Set.of(), Set.of("STOCK01"))
                .validate(event(1, EventType.NEW, ""), LocalDate.of(2026, 10, 15)));
  }
}
