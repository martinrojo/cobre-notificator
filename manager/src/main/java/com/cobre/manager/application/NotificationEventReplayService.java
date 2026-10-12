package com.cobre.manager.application;

import com.cobre.manager.domain.exception.NotificationEventNotFoundException;
import com.cobre.manager.domain.exception.ReplayNotAllowedException;
import com.cobre.manager.domain.exception.ReplayPublishException;
import com.cobre.manager.domain.model.NotificationEvent;
import com.cobre.manager.domain.model.ReplayRequested;
import com.cobre.manager.domain.port.in.ReplayNotificationEventUseCase;
import com.cobre.manager.domain.port.out.NotificationEventRepository;
import com.cobre.manager.domain.port.out.ReplayRequestPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class NotificationEventReplayService implements ReplayNotificationEventUseCase {

  private static final Logger log = LoggerFactory.getLogger(NotificationEventReplayService.class);
  static final String METRIC = "manager.replay.requests";

  private final NotificationEventRepository repository;
  private final ReplayRequestPublisher publisher;
  private final Clock clock;
  private final MeterRegistry meterRegistry;

  public NotificationEventReplayService(
      NotificationEventRepository repository,
      ReplayRequestPublisher publisher,
      Clock clock,
      MeterRegistry meterRegistry) {
    this.repository = repository;
    this.publisher = publisher;
    this.clock = clock;
    this.meterRegistry = meterRegistry;
    // Registered at 0: increase() misses the first event of a series that starts at 1.
    List.of("published", "in_progress", "not_allowed", "not_found", "unavailable")
        .forEach(outcome -> meterRegistry.counter(METRIC, "outcome", outcome));
  }

  @Override
  public void replay(String clientId, String id) {
    var requestedAt = clock.instant();
    NotificationEvent event =
        repository
            .findById(clientId, id)
            .orElseThrow(
                () -> {
                  count("not_found");
                  return NotificationEventNotFoundException.of(id);
                });
    switch (event.replayDecision()) {
      // The notificator identifies the event by (event_id, client_id), both from the row.
      case PUBLISH ->
          publish(
              id, new ReplayRequested(event.eventId(), clientId, event.generation(), requestedAt));
      case ALREADY_IN_PROGRESS -> {
        count("in_progress");
        log.atDebug()
            .addKeyValue("notification_event_id", id)
            .addKeyValue("event_id", event.eventId())
            .log("Replay already in progress");
      }
      case NOT_ALLOWED -> {
        count("not_allowed");
        log.atDebug()
            .addKeyValue("notification_event_id", id)
            .addKeyValue("event_id", event.eventId())
            .addKeyValue("delivery_status", event.deliveryStatus())
            .log("Replay not allowed");
        throw ReplayNotAllowedException.of(id, event.deliveryStatus());
      }
    }
  }

  private void publish(String id, ReplayRequested request) {
    try {
      publisher.publish(request);
    } catch (ReplayPublishException e) {
      count("unavailable");
      log.atError()
          .setCause(e)
          .addKeyValue("notification_event_id", id)
          .addKeyValue("event_id", request.eventId())
          .log("Replay publish failed");
      throw e;
    }
    count("published");
    log.atInfo()
        .addKeyValue("notification_event_id", id)
        .addKeyValue("event_id", request.eventId())
        .log("Replay requested");
  }

  private void count(String outcome) {
    meterRegistry.counter(METRIC, "outcome", outcome).increment();
  }
}
