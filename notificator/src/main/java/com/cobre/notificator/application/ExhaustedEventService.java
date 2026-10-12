package com.cobre.notificator.application;

import com.cobre.notificator.domain.model.DeliveryStatus;
import com.cobre.notificator.domain.port.in.FailExhaustedEventUseCase;
import com.cobre.notificator.domain.port.out.NotificationEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** DLT side: only the generation and attempt still current may close the event as FAILED. */
@Service
public class ExhaustedEventService implements FailExhaustedEventUseCase {

  private static final Logger log = LoggerFactory.getLogger(ExhaustedEventService.class);

  private final NotificationEventRepository events;
  private final MeterRegistry meterRegistry;

  public ExhaustedEventService(NotificationEventRepository events, MeterRegistry meterRegistry) {
    this.events = events;
    this.meterRegistry = meterRegistry;
  }

  @Override
  public void failExhausted(String eventId, String clientId, int generation, int attempt) {
    // null record: keep the data of the attempt that actually failed.
    if (!events.recordAttempt(
        eventId, clientId, generation, attempt, DeliveryStatus.FAILED, null)) {
      meterRegistry.counter(DeliveryService.DISCARDED, "reason", "dlt_ignored").increment();
      log.atDebug()
          .addKeyValue("event_id", eventId)
          .addKeyValue("generation", generation)
          .addKeyValue("attempt", attempt)
          .log("DLT message ignored");
      return;
    }
    meterRegistry
        .counter(DeliveryService.DELIVERIES, "outcome", "failed", "client_id", clientId)
        .increment();
    log.atInfo()
        .addKeyValue("event_id", eventId)
        .addKeyValue("client_id", clientId)
        .addKeyValue("attempt", attempt)
        .log("Event failed, retries exhausted");
  }
}
