package dev.huanling.traderecon.reconciliation;

import dev.huanling.traderecon.domain.*;
import dev.huanling.traderecon.ingestion.EventFingerprint;
import java.util.*;

/** Pure version projection of one source/execution. No cross-source matching yet. */
public final class ExecutionProjector {
  public record Projection(String status, TradeEvent current, List<String> reasons) {}

  public Projection project(List<TradeEvent> input) {
    if (input.isEmpty()) return blocked("MISSING_NEW");
    var sorted = new TreeMap<Integer, TradeEvent>();
    var first = input.getFirst();
    for (var e : input) {
      if (!e.key().equals(first.key()) || e.source() != first.source())
        throw new IllegalArgumentException("Project one source and execution");
      var old = sorted.putIfAbsent(e.revision(), e);
      if (old != null && !EventFingerprint.sameRevision(old, e))
        return blocked("REVISION_CONFLICT");
    }
    if (sorted.firstKey() != 1) return blocked("MISSING_NEW");
    int expected = 1;
    TradeEvent previous = null;
    for (var e : sorted.values()) {
      if (e.revision() != expected++) return blocked("VERSION_GAP");
      if (previous != null && previous.eventType() == EventType.CANCEL)
        return blocked("AFTER_CANCEL");
      if (e.eventType() == EventType.CANCEL
          && (previous == null
              || !EventFingerprint.business(previous).equals(EventFingerprint.business(e))))
        return blocked("INVALID_CANCEL_PAYLOAD");
      previous = e;
    }
    return new Projection(
        previous.eventType() == EventType.CANCEL ? "CANCELLED" : "ACTIVE", previous, List.of());
  }

  private Projection blocked(String reason) {
    return new Projection("DATA_BLOCKED", null, List.of(reason));
  }
}
