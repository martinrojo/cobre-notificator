package com.cobre.notificator.application;

import com.cobre.notificator.config.DeliveryProperties;
import com.cobre.notificator.domain.model.NotificationEvent;
import com.cobre.notificator.domain.model.RetrySchedule;
import com.cobre.notificator.domain.port.in.RecoverStaleDeliveriesUseCase;
import com.cobre.notificator.domain.port.out.NotificationEventRepository;
import com.cobre.notificator.domain.port.out.RetryPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Resumes events whose attempt stopped advancing (crash, rebalance, lost publish) without touching
 * attempts: the retry consumer claims the next attempt with its normal CAS. The conditional refresh
 * of updated_at makes concurrent instances publish once.
 */
@Service
public class StaleDeliveryRecoveryService implements RecoverStaleDeliveriesUseCase {

  public static final String RECOVERY_EVENTS = "notificator.recovery.events";

  private static final Logger log = LoggerFactory.getLogger(StaleDeliveryRecoveryService.class);

  private final NotificationEventRepository events;
  private final RetryPublisher retryPublisher;
  private final MeterRegistry meterRegistry;
  private final RetrySchedule schedule;
  private final Duration lease;
  private final int batchSize;

  public StaleDeliveryRecoveryService(
      NotificationEventRepository events,
      RetryPublisher retryPublisher,
      MeterRegistry meterRegistry,
      DeliveryProperties properties) {
    this.events = events;
    this.retryPublisher = retryPublisher;
    this.meterRegistry = meterRegistry;
    DeliveryProperties.Retry retry = properties.retry();
    this.schedule = new RetrySchedule(retry.attempts(), retry.delay(), retry.multiplier());
    this.lease = properties.lease();
    this.batchSize = properties.recovery().batchSize();
    // Known tag values exist at 0 from startup, so increase() does not miss the first one.
    List.of("republished", "failed")
        .forEach(action -> meterRegistry.counter(RECOVERY_EVENTS, "action", action));
  }

  @Override
  public void recover() {
    for (NotificationEvent event : events.findStale(lease, batchSize)) {
      int k = event.attempts();
      Duration age = schedule.delayAfter(k).plus(lease);
      if (k >= schedule.attempts()) {
        if (events.failIfStale(event.eventId(), event.clientId(), event.generation(), k, age)) {
          count("failed");
          // A FAILED closed here is a final outcome like any other.
          meterRegistry
              .counter(
                  DeliveryService.DELIVERIES, "outcome", "failed", "client_id", event.clientId())
              .increment();
          log.atInfo()
              .addKeyValue("event_id", event.eventId())
              .addKeyValue("client_id", event.clientId())
              .addKeyValue("attempt", k)
              .log("Stale last attempt failed");
        }
      } else if (events.refreshIfStale(
          event.eventId(), event.clientId(), event.generation(), k, age)) {
        republish(event, k);
      }
    }
  }

  private void republish(NotificationEvent event, int k) {
    try {
      retryPublisher.publish(event, k);
      count("republished");
    } catch (RuntimeException e) {
      // The row stays refreshed: the next threshold retries the publish.
      log.atError()
          .setCause(e)
          .addKeyValue("event_id", event.eventId())
          .addKeyValue("client_id", event.clientId())
          .addKeyValue("attempt", k)
          .log("Recovery publish failed");
    }
  }

  private void count(String action) {
    meterRegistry.counter(RECOVERY_EVENTS, "action", action).increment();
  }
}
