package dev.huanling.traderecon.reconciliation;

import dev.huanling.traderecon.domain.*;
import dev.huanling.traderecon.ingestion.EventFingerprint;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Hash grouping plus per-execution revision sorting; deterministic, no database access. */
public final class DefaultReconciliationEngine implements ReconciliationEngine {
  private final ExecutionProjector projector = new ExecutionProjector();

  @Override
  public List<Result> reconcile(Input input) {
    if (!RuleSet.VERSION.equals(input.rulesVersion()))
      throw new IllegalArgumentException("Unsupported rules version: " + input.rulesVersion());
    Map<ExecutionKey, Map<Source, List<TradeEvent>>> groups = new HashMap<>();
    for (var event : input.frozenEvents()) {
      if (!event.key().datasetId().equals(input.datasetId())
          || !event.key().tradeDate().equals(input.businessDate()))
        throw new IllegalArgumentException("Event outside snapshot dataset/date");
      groups
          .computeIfAbsent(event.key(), k -> new EnumMap<>(Source.class))
          .computeIfAbsent(event.source(), s -> new ArrayList<>())
          .add(event);
    }
    return groups.entrySet().stream()
        .sorted(Comparator.comparing(e -> e.getKey().executionId()))
        .map(e -> compare(input, e.getKey(), e.getValue()))
        .toList();
  }

  private Result compare(Input input, ExecutionKey key, Map<Source, List<TradeEvent>> sides) {
    Map<Source, ExecutionProjector.Projection> projections = new EnumMap<>(Source.class);
    List<String> reasons = new ArrayList<>();
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("rulesVersion", input.rulesVersion());
    evidence.put("executionKey", key);
    for (var source : Source.values()) {
      var events = sides.getOrDefault(source, List.of());
      // Sorted distinct canonical business revisions ignore delivery IDs, notes and duplicate rows.
      var history =
          events.stream().map(DefaultReconciliationEngine::canonical).distinct().sorted().toList();
      evidence.put(source.name() + "_versions", history);
      if (!events.isEmpty()) {
        var projection = projector.project(events);
        projections.put(source, projection);
        evidence.put(source.name() + "_projection", projection.status());
        if (projection.current() != null)
          evidence.put(source.name() + "_current", business(projection.current()));
        projection.reasons().forEach(r -> reasons.add(source.name() + "_" + r));
      } else evidence.put(source.name() + "_projection", "ABSENT");
    }
    ReconStatus status;
    if (!reasons.isEmpty()) status = ReconStatus.DATA_BLOCKED;
    else if (projections.size() < 2) {
      var received =
          Objects.requireNonNull(input.firstReceivedAt().get(key), "Missing receipt time");
      if (received.isAfter(input.asOf()))
        throw new IllegalArgumentException("Receipt after snapshot");
      boolean expired = !input.asOf().isBefore(received.plus(RuleSet.WAIT_WINDOW));
      boolean complete = input.completeSources().containsAll(EnumSet.allOf(Source.class));
      status = expired || complete ? ReconStatus.BREAK : ReconStatus.WAITING;
      reasons.add(projections.containsKey(Source.INTERNAL) ? "MISSING_BROKER" : "MISSING_INTERNAL");
      reasons.add(
          complete
              ? "BOTH_FEEDS_COMPLETE"
              : expired ? "WAIT_WINDOW_EXPIRED" : "WITHIN_WAIT_WINDOW");
    } else {
      var a = projections.get(Source.INTERNAL).current();
      var b = projections.get(Source.BROKER).current();
      diff(reasons, "ACCOUNT", a.accountId(), b.accountId());
      diff(reasons, "INSTRUMENT", a.instrumentId(), b.instrumentId());
      diff(reasons, "SIDE", a.side(), b.side());
      diff(reasons, "CURRENCY", a.money().currency(), b.money().currency());
      if (a.money().quantity().compareTo(b.money().quantity()) != 0)
        reasons.add("QUANTITY_MISMATCH");
      if (a.money().price().compareTo(b.money().price()) != 0) reasons.add("PRICE_MISMATCH");
      diff(
          reasons,
          "CANCELLATION",
          a.eventType() == EventType.CANCEL,
          b.eventType() == EventType.CANCEL);
      status = reasons.isEmpty() ? ReconStatus.MATCHED : ReconStatus.BREAK;
      if (reasons.isEmpty())
        reasons.add(a.eventType() == EventType.CANCEL ? "MATCHED_CANCELLED" : "MATCHED_FIELDS");
    }
    evidence.put("status", status.name());
    evidence.put("reasonCodes", List.copyOf(reasons));
    // Evidence has only fixed-order maps/lists, validated IDs, enums and normalized decimal
    // strings.
    String hash = EventFingerprint.sha(evidence.toString().getBytes(StandardCharsets.UTF_8));
    return new Result(key, status, reasons, evidence, hash);
  }

  private static void diff(List<String> reasons, String field, Object a, Object b) {
    if (!a.equals(b)) reasons.add(field + "_MISMATCH");
  }

  private static Map<String, Object> business(TradeEvent e) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("revision", e.revision());
    value.put("type", e.eventType().name());
    value.put("account", e.accountId());
    value.put("instrument", e.instrumentId());
    value.put("side", e.side().name());
    value.put("currency", e.money().currency().name());
    value.put("quantity", e.money().quantity().toPlainString());
    value.put("price", e.money().price().toPlainString());
    return Collections.unmodifiableMap(value);
  }

  private static String canonical(TradeEvent e) {
    return business(e).toString();
  }
}
