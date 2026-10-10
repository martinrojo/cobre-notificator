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
  }

  @Override
  public void replay(String clientId, String eventId) {
    var requestedAt = clock.instant();
    NotificationEvent event =
        repository
            .findById(clientId, eventId)
            .orElseThrow(
                () -> {
                  count("not_found");
                  return NotificationEventNotFoundException.of(eventId);
                });
    switch (event.replayDecision()) {
      case PUBLISH -> publish(new ReplayRequested(eventId, clientId, requestedAt));
      case ALREADY_IN_PROGRESS -> {
        count("in_progress");
        log.debug("Replay already in progress event_id={}", eventId);
      }
      case NOT_ALLOWED -> {
        count("not_allowed");
        log.debug(
            "Replay not allowed event_id={} delivery_status={}", eventId, event.deliveryStatus());
        throw ReplayNotAllowedException.of(eventId, event.deliveryStatus());
      }
    }
  }

  private void publish(ReplayRequested request) {
    try {
      publisher.publish(request);
    } catch (ReplayPublishException e) {
      count("unavailable");
      log.error("Replay publish failed event_id={}", request.eventId(), e);
      throw e;
    }
    count("published");
    log.info("Replay requested event_id={} client_id={}", request.eventId(), request.clientId());
  }

  private void count(String outcome) {
    meterRegistry.counter(METRIC, "outcome", outcome).increment();
  }
}
