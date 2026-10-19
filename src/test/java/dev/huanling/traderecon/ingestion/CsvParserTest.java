package dev.huanling.traderecon.ingestion;

import static org.junit.jupiter.api.Assertions.*;

import dev.huanling.traderecon.domain.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class CsvParserTest {
  static final String HEADER = String.join(",", CsvParser.HEADER) + "\n";
  static final String ROW =
      "ev1,E1,1,NEW,ACC01,STOCK01,BUY,USD,50,100.25,2026-10-15,2026-10-15T10:00:00+01:00,";
  CsvParser parser = new CsvParser();

  CsvParser.Parsed parse(String text) {
    return parser.parse(
        text.getBytes(StandardCharsets.UTF_8),
        UUID.randomUUID(),
        Source.INTERNAL,
        LocalDate.of(2026, 10, 15));
  }

  @Test
  void quotedMultilineUtf8AndTimezone() {
    var p = parse(HEADER + ROW + "\"中文, \"\"quoted\"\"\nnext\"\n");
    assertTrue(p.errors().isEmpty());
    assertEquals("中文, \"quoted\"\nnext", p.rows().getFirst().event().note());
    assertEquals("2026-10-15T09:00:00Z", p.rows().getFirst().event().occurredAt().toString());
  }

  @Test
  void decimalsInvalidAndFieldSpecific() {
    for (String v : List.of("0", "-1", "1.0000001", "100000000000000", "1e2", "")) {
      var p = parse(HEADER + ROW.replace(",100.25,", "," + v + ","));
      assertEquals("price", p.errors().getFirst().field(), v);
    }
  }

  @Test
  void invalidDateEnumOffsetAndNote() {
    for (String row :
        List.of(
            ROW.replace("BUY", "WRONG"),
            ROW.replace("2026-10-15,", "2026-10-16,"),
            ROW.replace("T10:00:00+01:00", "T10:00:00"),
            ROW + "x".repeat(501))) {
      assertFalse(parse(HEADER + row).errors().isEmpty());
    }
  }

  @Test
  void rejectsBadHeaderFieldCountAndQuotes() {
    for (String s :
        List.of(
            "wrong\n",
            HEADER + ROW + "a,b",
            HEADER + ROW + "\"unclosed",
            HEADER.replace("event_id", "execution_id"))) {
      assertEquals(400, assertThrows(ImportFault.class, () -> parse(s)).status);
    }
  }

  @Test
  void rejectsMalformedUtf8() {
    assertEquals(
        400,
        assertThrows(
                ImportFault.class,
                () ->
                    parser.parse(
                        new byte[] {(byte) 0xC3, 0x28},
                        UUID.randomUUID(),
                        Source.INTERNAL,
                        LocalDate.now()))
            .status);
  }

  @Test
  void enforcesByteAndRowLimits() {
    assertEquals(
        413,
        assertThrows(
                ImportFault.class,
                () ->
                    parser.parse(
                        new byte[CsvParser.MAX_BYTES + 1],
                        UUID.randomUUID(),
                        Source.INTERNAL,
                        LocalDate.now()))
            .status);
    assertEquals(
        413,
        assertThrows(ImportFault.class, () -> parse(HEADER + (ROW + "\n").repeat(20001))).status);
  }

  @Test
  void headerOnlyAndBomAreAllowed() {
    assertEquals(0, parse(HEADER).rows().size());
    assertTrue(parse("\uFEFF" + HEADER + ROW).errors().isEmpty());
  }

  @Test
  void multipleInvalidRowsAreReported() {
    var p = parse(HEADER + (ROW.replace("BUY", "INVALID") + "\n").repeat(2));
    assertEquals(2, p.errors().size());
    assertEquals(2, p.errors().get(1).row());
  }
}
