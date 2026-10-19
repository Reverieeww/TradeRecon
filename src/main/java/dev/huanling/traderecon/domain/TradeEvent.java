package dev.huanling.traderecon.domain;

import java.time.Instant;
import java.util.Objects;

public record TradeEvent(
    ExecutionKey key,
    Source source,
    String eventId,
    int revision,
    EventType eventType,
    String accountId,
    String instrumentId,
    Side side,
    MoneyFields money,
    Instant occurredAt,
    String note) {
  public TradeEvent {
    Objects.requireNonNull(key);
    Objects.requireNonNull(source);
    Identifiers.requireValid(eventId, "eventId");
    if (revision < 1) throw new IllegalArgumentException("revision must be positive");
    Objects.requireNonNull(eventType);
    if ((revision == 1) != (eventType == EventType.NEW))
      throw new IllegalArgumentException("revision 1 must be NEW; later revisions must not be NEW");
    Identifiers.requireValid(accountId, "accountId");
    Identifiers.requireValid(instrumentId, "instrumentId");
    Objects.requireNonNull(side);
    Objects.requireNonNull(money);
    Objects.requireNonNull(occurredAt);
    note = note == null ? "" : note;
    if (note.codePointCount(0, note.length()) > 500)
      throw new IllegalArgumentException("note exceeds 500 characters");
  }
}
