package dev.huanling.traderecon.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record ExecutionKey(UUID datasetId, LocalDate tradeDate, String executionId) {
  public ExecutionKey {
    Objects.requireNonNull(datasetId, "datasetId");
    Objects.requireNonNull(tradeDate, "tradeDate");
    Identifiers.requireValid(executionId, "executionId");
  }
}
