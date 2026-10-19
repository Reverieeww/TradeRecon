package dev.huanling.traderecon.ai;

import java.math.BigDecimal;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class AiSettings {
  private final Environment env;

  public AiSettings(Environment env) {
    this.env = env;
  }

  public String mode() {
    String mode = env.getProperty("app.ai-mode", "OFF");
    return java.util.Set.of("OFF", "MOCK", "LIVE").contains(mode) ? mode : "OFF";
  }

  public String model() {
    return mode().equals("LIVE") ? env.getProperty("app.ai.model", "") : "mock-v1";
  }

  public String key() {
    return env.getProperty("app.ai.key", "");
  }

  public String promptVersion() {
    return "investigation-v1";
  }

  public BigDecimal budget() {
    return decimal("budget-usd");
  }

  public BigDecimal inputRate() {
    return decimal("input-usd-per-million");
  }

  public BigDecimal outputRate() {
    return decimal("output-usd-per-million");
  }

  private BigDecimal decimal(String key) {
    try {
      return new BigDecimal(env.getProperty("app.ai." + key, "0"));
    } catch (Exception e) {
      return BigDecimal.ZERO;
    }
  }

  public int maxRequests() {
    return Math.max(0, env.getProperty("app.ai.max-requests", Integer.class, 0));
  }

  public String unavailable() {
    if (mode().equals("OFF")) return "AI_OFF";
    if (mode().equals("MOCK")) return null;
    if (key().isBlank()) return "MISSING_API_KEY";
    if (model().isBlank()) return "MISSING_MODEL";
    if (!env.getProperty("app.ai.live-approved", Boolean.class, false)) return "LIVE_NOT_APPROVED";
    if (budget().signum() <= 0
        || inputRate().signum() <= 0
        || outputRate().signum() <= 0
        || maxRequests() == 0) return "BUDGET_NOT_CONFIGURED";
    return null;
  }

  // UTF-8 request bytes conservatively bound input tokens; extra envelope headroom.
  // Reserve for TWO attempts and 2048 output tokens per attempt. Never refund uncertain calls.
  public BigDecimal reservation(int bytes) {
    return inputRate()
        .multiply(BigDecimal.valueOf(bytes + 4096L))
        .add(outputRate().multiply(BigDecimal.valueOf(2048)))
        .multiply(BigDecimal.valueOf(2))
        .divide(BigDecimal.valueOf(1000000), 6, java.math.RoundingMode.UP);
  }
}
