package dev.huanling.traderecon.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyFieldsTest {
  @Test
  void decimalsWithDifferentScalesAreEqual() {
    assertEquals(
        new MoneyFields(new BigDecimal("50"), new BigDecimal("100.25"), Currency.USD),
        new MoneyFields(new BigDecimal("50.000000"), new BigDecimal("100.250000"), Currency.USD));
  }

  @Test
  void rejectsRoundingOverflowZeroAndNegative() {
    for (String s : new String[] {"1.0000001", "100000000000000", "0", "-1"})
      assertThrows(
          IllegalArgumentException.class,
          () -> new MoneyFields(new BigDecimal(s), BigDecimal.ONE, Currency.USD),
          s);
  }

  @Test
  void acceptsMaximumAndMinimum() {
    assertDoesNotThrow(
        () ->
            new MoneyFields(
                new BigDecimal("99999999999999.999999"), new BigDecimal("0.000001"), Currency.GBP));
  }
}
