package dev.huanling.traderecon.ai;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.ai.worker-enabled", havingValue = "true", matchIfMissing = true)
public class AiWorker {
  private final AiService service;
  private final ExecutorService workers = Executors.newFixedThreadPool(2);
  private final Semaphore slots = new Semaphore(2);

  public AiWorker(AiService service) {
    this.service = service;
  }

  @Scheduled(fixedDelay = 500)
  public void tick() {
    if (!slots.tryAcquire()) return;
    try {
      var job = service.claim();
      if (job == null) {
        slots.release();
        return;
      }
      workers.submit(
          () -> {
            try {
              service.execute(job);
            } catch (RuntimeException e) {
              org.slf4j.LoggerFactory.getLogger(AiWorker.class)
                  .warn("AI job deferred to expiry: {}", e.getClass().getSimpleName());
            } finally {
              slots.release();
            }
          });
    } catch (RuntimeException e) {
      slots.release();
      org.slf4j.LoggerFactory.getLogger(AiWorker.class)
          .warn("AI queue unavailable: {}", e.getClass().getSimpleName());
    }
  }

  @PreDestroy
  void stop() {
    workers.shutdownNow();
  }
}
