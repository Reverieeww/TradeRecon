package dev.huanling.traderecon.ingestion;

import java.util.*;

public class ImportFault extends RuntimeException {
  public record FieldError(int row, String field, String message) {}

  public final int status;
  public final String code;
  public final List<FieldError> errors;
  public UUID batchId;

  public ImportFault(int status, String code, List<FieldError> errors) {
    super(code);
    this.status = status;
    this.code = code;
    this.errors = List.copyOf(errors);
  }

  public static ImportFault of(int status, String code, int row, String field, String message) {
    return new ImportFault(status, code, List.of(new FieldError(row, field, message)));
  }
}
