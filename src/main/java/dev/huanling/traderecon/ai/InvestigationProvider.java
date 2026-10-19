package dev.huanling.traderecon.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;

/** Read-only provider boundary: no repository, tools or review-state capability. */
public interface InvestigationProvider {
  record Reply(String raw, String requestId, String model, JsonNode usage, int attempts) {}

  Reply analyse(JsonNode evidence, Duration timeout) throws Exception;
}
