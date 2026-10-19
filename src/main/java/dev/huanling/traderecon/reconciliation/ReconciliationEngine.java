package dev.huanling.traderecon.reconciliation;

import dev.huanling.traderecon.domain.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Pure deterministic rule contract. No Spring/SQL/AI dependencies. */
public interface ReconciliationEngine {
  List<Result> reconcile(Input input);

  record Input(
      UUID datasetId,
      LocalDate businessDate,
      Instant asOf,
      String rulesVersion,
      List<TradeEvent> frozenEvents,
      Map<ExecutionKey, Instant> firstReceivedAt,
      Set<Source> completeSources) {
    public Input {
      Objects.requireNonNull(datasetId);
      Objects.requireNonNull(businessDate);
      Objects.requireNonNull(asOf);
      Objects.requireNonNull(rulesVersion);
      frozenEvents = List.copyOf(frozenEvents);
      firstReceivedAt = Map.copyOf(firstReceivedAt);
      completeSources = Set.copyOf(completeSources);
    }
  }

  record Result(
      ExecutionKey key,
      ReconStatus status,
      List<String> reasons,
      Map<String, Object> evidence,
      String evidenceHash) {
    public Result {
      Objects.requireNonNull(key);
      Objects.requireNonNull(status);
      reasons = List.copyOf(reasons);
      evidence = Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
      Objects.requireNonNull(evidenceHash);
    }
  }
}
