package dev.huanling.traderecon.ingestion;

import dev.huanling.traderecon.domain.TradeEvent;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public final class EventFingerprint {
  private EventFingerprint() {}

  public static String sha(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static List<Object> business(TradeEvent e) {
    return List.of(e.key(), e.accountId(), e.instrumentId(), e.side(), e.money());
  }

  public static boolean sameRevision(TradeEvent a, TradeEvent b) {
    return a.revision() == b.revision()
        && a.eventType() == b.eventType()
        && business(a).equals(business(b));
  }

  // Identifiers cannot contain separators. Notes are provenance, not business identity.
  public static String payload(TradeEvent e) {
    return sha(
        (e.key().executionId()
                + "|"
                + e.key().tradeDate()
                + "|"
                + e.revision()
                + "|"
                + e.eventType()
                + "|"
                + e.accountId()
                + "|"
                + e.instrumentId()
                + "|"
                + e.side()
                + "|"
                + e.money().currency()
                + "|"
                + e.money().quantity().toPlainString()
                + "|"
                + e.money().price().toPlainString()
                + "|"
                + e.occurredAt())
            .getBytes(StandardCharsets.UTF_8));
  }
}
