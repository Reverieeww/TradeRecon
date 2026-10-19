package dev.huanling.traderecon.reconciliation;

import java.time.Duration;

public final class RuleSet {
  private RuleSet() {}

  public static final String VERSION = "recon-v1";
  public static final Duration WAIT_WINDOW = Duration.ofMinutes(10);
}
