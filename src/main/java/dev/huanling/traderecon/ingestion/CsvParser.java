package dev.huanling.traderecon.ingestion;

import dev.huanling.traderecon.domain.*;
import dev.huanling.traderecon.domain.Currency;
import java.io.*;
import java.math.BigDecimal;
import java.nio.*;
import java.nio.charset.*;
import java.time.*;
import java.util.*;
import org.apache.commons.csv.*;
import org.springframework.stereotype.Component;

@Component
public class CsvParser {
  public static final int MAX_BYTES = 10 * 1024 * 1024, MAX_ROWS = 20000;
  public static final List<String> HEADER =
      List.of(
          "event_id",
          "execution_id",
          "revision",
          "event_type",
          "account_id",
          "instrument_id",
          "side",
          "currency",
          "quantity",
          "price",
          "trade_date",
          "occurred_at",
          "note");

  public record Row(int number, Map<String, String> raw, TradeEvent event) {}

  public record Parsed(List<Row> rows, List<ImportFault.FieldError> errors) {}

  public Parsed parse(byte[] bytes, UUID dataset, Source source, LocalDate date) {
    if (bytes.length > MAX_BYTES)
      throw ImportFault.of(413, "FILE_TOO_LARGE", 0, "file", "Maximum 10 MiB");
    List<Row> rows = new ArrayList<>();
    List<ImportFault.FieldError> errors = new ArrayList<>();
    try {
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      if (text.startsWith("\uFEFF")) text = text.substring(1);
      if (text.indexOf('\0') >= 0)
        throw ImportFault.of(400, "MALFORMED_CSV", 0, "file", "NUL characters are not supported");
      try (var parser =
          CSVFormat.RFC4180
              .builder()
              .setHeader()
              .setSkipHeaderRecord(true)
              .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
              .get()
              .parse(new StringReader(text))) {
        if (!parser.getHeaderNames().equals(HEADER))
          throw ImportFault.of(
              400, "MALFORMED_CSV", 0, "header", "Expected exact documented column order");
        for (var r : parser) {
          int n = (int) r.getRecordNumber();
          if (n > MAX_ROWS)
            throw ImportFault.of(413, "TOO_MANY_ROWS", n, "file", "Maximum 20000 data records");
          if (r.size() != HEADER.size())
            throw ImportFault.of(400, "MALFORMED_CSV", n, "row", "Expected 13 fields");
          Map<String, String> raw = new LinkedHashMap<>(r.toMap());
          String field = "event_id";
          TradeEvent event = null;
          try {
            Identifiers.requireValid(r.get(field), field);
            field = "execution_id";
            String execution = Identifiers.requireValid(r.get(field), field);
            field = "revision";
            int rev = Integer.parseInt(r.get(field));
            if (rev < 1) throw new IllegalArgumentException("Must be positive");
            field = "event_type";
            EventType type = EventType.valueOf(r.get(field));
            if ((rev == 1) != (type == EventType.NEW))
              throw new IllegalArgumentException("NEW only at revision 1");
            field = "account_id";
            String account = Identifiers.requireValid(r.get(field), field);
            field = "instrument_id";
            String instrument = Identifiers.requireValid(r.get(field), field);
            field = "side";
            Side side = Side.valueOf(r.get(field));
            field = "currency";
            Currency currency = Currency.valueOf(r.get(field));
            field = "quantity";
            BigDecimal qty = decimal(r.get(field));
            field = "price";
            BigDecimal price = decimal(r.get(field));
            field = "trade_date";
            LocalDate tradeDate = LocalDate.parse(r.get(field));
            if (!date.equals(tradeDate))
              throw new IllegalArgumentException("Must equal business_date");
            field = "occurred_at";
            Instant at = OffsetDateTime.parse(r.get(field)).toInstant();
            field = "note";
            String note = r.get(field);
            if (note.codePointCount(0, note.length()) > 500)
              throw new IllegalArgumentException("Maximum 500 characters");
            event =
                new TradeEvent(
                    new ExecutionKey(dataset, tradeDate, execution),
                    source,
                    r.get("event_id"),
                    rev,
                    type,
                    account,
                    instrument,
                    side,
                    new MoneyFields(qty, price, currency),
                    at,
                    note);
          } catch (IllegalArgumentException | java.time.DateTimeException e) {
            errors.add(
                new ImportFault.FieldError(n, field, "Invalid " + field + ": " + e.getMessage()));
          }
          rows.add(new Row(n, Map.copyOf(raw), event));
        }
      }
      return new Parsed(List.copyOf(rows), List.copyOf(errors));
    } catch (ImportFault e) {
      throw e;
    } catch (IOException | IllegalArgumentException | UncheckedIOException e) {
      throw ImportFault.of(
          400, "MALFORMED_CSV", rows.size() + 1, "file", "Invalid UTF-8 or CSV quoting/header");
    }
  }

  private BigDecimal decimal(String s) {
    if (!s.matches("[0-9]{1,14}(\\.[0-9]{1,6})?"))
      throw new IllegalArgumentException(
          "Positive decimal; max 14 integer and 6 fractional digits");
    var n = new BigDecimal(s);
    if (n.signum() <= 0) throw new IllegalArgumentException("Must be positive");
    return n.setScale(6);
  }
}
