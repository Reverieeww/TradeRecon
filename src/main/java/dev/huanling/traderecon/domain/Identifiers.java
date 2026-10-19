package dev.huanling.traderecon.domain;

public final class Identifiers {
  private Identifiers() {}

  public static String requireValid(String value, String field) {
    if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}"))
      throw new IllegalArgumentException(field + " must contain 1..64 letters, digits, _ or -");
    return value;
  }
}
