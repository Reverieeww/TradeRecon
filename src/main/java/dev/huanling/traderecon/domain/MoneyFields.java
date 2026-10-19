package dev.huanling.traderecon.domain;

import java.math.BigDecimal;
import java.util.Objects;

public record MoneyFields(BigDecimal quantity, BigDecimal price, Currency currency) {
  private static final BigDecimal LIMIT = new BigDecimal("100000000000000");

  public MoneyFields {
    quantity = exact(quantity, "quantity");
    price = exact(price, "price");
    Objects.requireNonNull(currency, "currency");
  }

  private static BigDecimal exact(BigDecimal value, String field) {
    if (value == null || value.signum() <= 0 || value.scale() > 6 || value.compareTo(LIMIT) >= 0)
      throw new IllegalArgumentException(
          field + " must be positive NUMERIC(20,6), without rounding");
    return value.setScale(6);
  }
}
