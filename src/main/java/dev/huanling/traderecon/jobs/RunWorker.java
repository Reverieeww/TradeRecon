package dev.huanling.traderecon.jobs;

import dev.huanling.traderecon.reconciliation.ReconciliationService;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true", matchIfMissing = true)
public class RunWorker {
  private static final Logger log = LoggerFactory.getLogger(RunWorker.class);
  private final LeaseService leases;
  private final ReconciliationService service;
  private final ScheduledExecutorService heartbeats =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            var t = new Thread(r, "recon-heartbeat");
            t.setDaemon(true);
            return t;
          });

  public RunWorker(LeaseService leases, ReconciliationService service) {
    this.leases = leases;
    this.service = service;
  }

  @Scheduled(fixedDelay = 1000)
  public void poll() {
    try {
      var next = leases.claimNext();
      if (next.isEmpty()) return;
      var lease = next.get();
      var heartbeat =
          heartbeats.scheduleAtFixedRate(
              () -> {
                try {
                  if (!leases.heartbeat(lease))
                    log.warn("Worker lost lease for run {}", lease.runId());
                } catch (RuntimeException e) {
                  log.warn("Heartbeat unavailable for run {}", lease.runId());
                }
              },
              10,
              10,
              TimeUnit.SECONDS);
      try {
        service.execute(lease);
      } finally {
        heartbeat.cancel(false);
      }
    } catch (RuntimeException e) {
      log.warn("Reconciliation worker iteration failed: {}", e.getClass().getSimpleName());
    }
  }

  @PreDestroy
  public void stop() {
    heartbeats.shutdownNow();
  }
}
