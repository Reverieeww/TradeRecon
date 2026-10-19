package dev.huanling.traderecon.ingestion;

import dev.huanling.traderecon.domain.TradeEvent;
import java.time.LocalDate;
import java.util.Set;

/** Field-level checks only; version-chain checks belong to the day-2 projector. */
public final class BatchValidator {
  private final Set<String> accounts;
  private final Set<String> instruments;

  public BatchValidator(Set<String> accounts, Set<String> instruments) {
    this.accounts = Set.copyOf(accounts);
    this.instruments = Set.copyOf(instruments);
  }

  public void validate(TradeEvent event, LocalDate businessDate) {
    if (!event.key().tradeDate().equals(businessDate))
      throw new IllegalArgumentException("trade_date differs from business_date");
    if (!accounts.contains(event.accountId()))
      throw new IllegalArgumentException("unknown account_id");
    if (!instruments.contains(event.instrumentId()))
      throw new IllegalArgumentException("unknown instrument_id");
  }
}
