package dev.huanling.traderecon.reconciliation;

import static org.junit.jupiter.api.Assertions.*;

import dev.huanling.traderecon.domain.*;
import dev.huanling.traderecon.ingestion.CsvParser;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class JuneFixtureTest {
  @Test
  void syntheticJuneDatesPreserveReconciliationBehaviour() throws Exception {
    UUID dataset = UUID.randomUUID();
    LocalDate date = LocalDate.of(2026, 6, 18);
    Instant received = Instant.parse("2026-06-18T09:00:00Z");
    var events = new ArrayList<TradeEvent>();
    var first = new HashMap<ExecutionKey, Instant>();
    var files =
        List.of(
            "01_internal.csv", "02_broker.csv", "03_broker_fix.csv", "04_internal_fill_gap.csv");
    for (int i = 0; i < files.size(); i++) {
      String name = files.get(i);
      var parsed =
          new CsvParser()
              .parse(
                  Files.readAllBytes(Path.of("data/june-demo", name)),
                  dataset,
                  name.contains("internal") ? Source.INTERNAL : Source.BROKER,
                  date);
      assertTrue(parsed.errors().isEmpty(), name);
      for (var row : parsed.rows()) {
        events.add(row.event());
        first.put(row.event().key(), received);
      }
      if (i == 1) {
        var initial =
            new DefaultReconciliationEngine()
                .reconcile(
                    new ReconciliationEngine.Input(
                        dataset,
                        date,
                        received.plusSeconds(60),
                        RuleSet.VERSION,
                        events,
                        first,
                        Set.of()));
        assertEquals(4, initial.size());
        assertEquals(1, initial.stream().filter(x -> x.status() == ReconStatus.MATCHED).count());
        assertTrue(initial.stream().anyMatch(x -> x.status() == ReconStatus.BREAK));
        assertTrue(initial.stream().anyMatch(x -> x.status() == ReconStatus.DATA_BLOCKED));
      }
    }
    var result =
        new DefaultReconciliationEngine()
            .reconcile(
                new ReconciliationEngine.Input(
                    dataset,
                    date,
                    received.plusSeconds(60),
                    RuleSet.VERSION,
                    events,
                    first,
                    Set.of()));
    assertEquals(4, result.size());
    assertTrue(result.stream().allMatch(x -> x.status() == ReconStatus.MATCHED));
  }
}
