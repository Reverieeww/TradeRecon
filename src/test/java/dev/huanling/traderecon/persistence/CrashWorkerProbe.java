package dev.huanling.traderecon.persistence;

import dev.huanling.traderecon.TradeReconApplication;
import dev.huanling.traderecon.domain.Source;
import dev.huanling.traderecon.ingestion.*;
import dev.huanling.traderecon.jobs.LeaseService;
import dev.huanling.traderecon.reconciliation.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Test-classpath-only fault probe. The parent kills precisely this child process at its marker. */
public class CrashWorkerProbe {
  public static void main(String[] args) {
    if (!System.getenv("SPRING_DATASOURCE_URL")
        .matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/traderecon_test"))
      throw new IllegalArgumentException("Dedicated local test DB required");
    try (var context =
        new SpringApplicationBuilder(TradeReconApplication.class)
            .run(
                "--server.port=0", "--app.worker.enabled=false", "--spring.main.banner-mode=off")) {
      String phase = args[0];
      UUID id = UUID.fromString(args[1]);
      UUID dataset = UUID.fromString(args[2]);
      if (phase.equals("import")) {
        var jdbc = context.getBean(JdbcTemplate.class);
        var mapper = context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
        var repository =
            new ImportRepository(jdbc, mapper) {
              @Override
              public void insertRows(UUID batch, List<RowOutcome> rows, boolean batched) {
                super.insertRows(batch, rows, batched);
                pause();
              }
            };
        new ImportService(
                repository,
                context.getBean(CsvParser.class),
                Clock.systemUTC(),
                context.getBean(PlatformTransactionManager.class))
            .ingest(dataset, Source.INTERNAL, LocalDate.of(2026, 10, 18), true, csv());
        return;
      }
      var lease = context.getBean(LeaseService.class).claim(id);
      var service = context.getBean(ReconciliationService.class);
      if (phase.equals("calculation")) {
        pause();
        return;
      }
      if (phase.equals("publication")) {
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
            .executeWithoutResult(
                t -> {
                  service.execute(lease);
                  pause();
                });
      } else {
        service.execute(lease);
        pause();
      }
    }
  }

  public static byte[] csv() {
    return ("event_id,execution_id,revision,event_type,account_id,instrument_id,side,currency,quantity,price,trade_date,occurred_at,note\nV1,E1,1,NEW,ACC01,STOCK01,BUY,USD,100,10,2026-10-18,2026-10-18T09:00:00Z,\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static void pause() {
    System.out.println("READY_TO_KILL");
    System.out.flush();
    try {
      new java.util.concurrent.CountDownLatch(1).await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
