package com.cobre.notificator.application;

import com.cobre.notificator.config.DeliveryProperties;
import com.cobre.notificator.domain.exception.RetryableDeliveryException;
import com.cobre.notificator.domain.model.AttemptRecord;
import com.cobre.notificator.domain.model.DeliveryOutcome;
import com.cobre.notificator.domain.model.DeliveryStatus;
import com.cobre.notificator.domain.model.NotificationEvent;
import com.cobre.notificator.domain.model.ReplayRequest;
import com.cobre.notificator.domain.model.Subscription;
import com.cobre.notificator.domain.model.TransactionEvent;
import com.cobre.notificator.domain.model.WebhookResult;
import com.cobre.notificator.domain.port.in.DeliverEventUseCase;
import com.cobre.notificator.domain.port.out.NotificationEventRepository;
import com.cobre.notificator.domain.port.out.SubscriptionRepository;
import com.cobre.notificator.domain.port.out.WebhookClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Claim first, then POST: only the process that won the claim of an attempt calls the webhook.
 * Every later write is conditional on that generation and attempt; losing it means another process
 * moved the event, so the result is discarded and no retry is scheduled. Never log the event
 * itself: its toString includes the content.
 */
@Service
public class DeliveryService implements DeliverEventUseCase {

  private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);
  static final String DELIVERIES = "notificator.deliveries";
  public static final String DISCARDED = "notificator.discarded";
  static final String REPLAYS = "notificator.replays";
  static final String E2E = "notificator.delivery.e2e";

  private final NotificationEventRepository events;
  private final SubscriptionRepository subscriptions;
  private final WebhookClient webhook;
  private final Clock clock;
  private final MeterRegistry meterRegistry;
  private final boolean allowHttp;

  public DeliveryService(
      NotificationEventRepository events,
      SubscriptionRepository subscriptions,
      WebhookClient webhook,
      Clock clock,
      MeterRegistry meterRegistry,
      DeliveryProperties properties) {
    this.events = events;
    this.subscriptions = subscriptions;
    this.webhook = webhook;
    this.clock = clock;
    this.meterRegistry = meterRegistry;
    this.allowHttp = properties.webhook().allowHttp();
    // Known tag values exist at 0 from startup, so increase() does not miss the first one.
    List.of("duplicate", "stale", "dlt_ignored")
        .forEach(reason -> meterRegistry.counter(DISCARDED, "reason", reason));
    List.of("started", "discarded")
        .forEach(outcome -> meterRegistry.counter(REPLAYS, "outcome", outcome));
  }

  @Override
  public void first(TransactionEvent event) {
    Optional<String> id = events.insertClaim(event);
    if (id.isEmpty()) {
      discard("duplicate", event.eventId(), 1);
      return;
    }
    deliver(id.get(), event, 0, 1);
  }

  @Override
  public void replay(ReplayRequest request) {
    if (!events.claimReplay(request)) {
      // Not FAILED, another generation, another client or no row: never says which.
      replayCount("discarded");
      log.atDebug()
          .addKeyValue("event_id", request.eventId())
          .addKeyValue("client_id", request.clientId())
          .addKeyValue("generation", request.generation())
          .log("Replay discarded");
      return;
    }
    int generation = request.deliveryGeneration();
    replayCount("started");
    // Delivery data comes from the row, never from the message.
    NotificationEvent row = events.findById(request.eventId(), request.clientId()).orElseThrow();
    log.atInfo()
        .addKeyValue("notification_event_id", row.id())
        .addKeyValue("event_id", request.eventId())
        .addKeyValue("client_id", request.clientId())
        .addKeyValue("generation", generation)
        .log("Replay started");
    deliver(row.id(), row.toTransactionEvent(), generation, 1);
  }

  @Override
  public void retry(String eventId, String clientId, int generation, int attempt) {
    if (!events.claimAttempt(eventId, clientId, generation, attempt)) {
      if (events.findById(eventId, clientId).isEmpty()) {
        // Retry topics are only reached after a successful claim: a missing row is a bug.
        log.atError()
            .addKeyValue("event_id", eventId)
            .addKeyValue("attempt", attempt)
            .log("Retry without event row");
        return;
      }
      discard("stale", eventId, attempt);
      return;
    }
    // Delivery data comes from the row, never from the message.
    NotificationEvent row = events.findById(eventId, clientId).orElseThrow();
    deliver(row.id(), row.toTransactionEvent(), generation, attempt);
  }

  private void deliver(String id, TransactionEvent event, int generation, int attempt) {
    Optional<Subscription> subscription =
        subscriptions.findActive(event.clientId(), event.eventType());
    if (subscription.isEmpty()) {
      AttemptRecord record =
          new AttemptRecord(clock.instant(), null, null, "No active subscription");
      if (complete(event, generation, attempt, DeliveryStatus.NOT_SUBSCRIBED, record)) {
        log.atDebug()
            .addKeyValue("event_id", event.eventId())
            .addKeyValue("client_id", event.clientId())
            .addKeyValue("event_type", event.eventType())
            .log("Event not subscribed");
      }
      return;
    }

    String url = subscription.get().webhookUrl();
    if (!isAllowed(url)) {
      fail(
          id,
          event,
          generation,
          attempt,
          new AttemptRecord(clock.instant(), url, null, "Webhook URL not allowed"));
      return;
    }

    WebhookResult result = webhook.post(url, id, event, attempt);
    AttemptRecord record = toRecord(url, result);
    switch (DeliveryOutcome.of(result)) {
      case DELIVERED -> {
        if (!events.markDelivered(event.eventId(), event.clientId(), generation, record)) {
          discard("stale", event.eventId(), attempt);
          return;
        }
        count("delivered", event.clientId());
        if (generation == 0) {
          // A replay keeps the original created_at: hours or days, not delivery latency.
          recordEndToEnd(event);
        }
        log.atInfo()
            .addKeyValue("notification_event_id", id)
            .addKeyValue("event_id", event.eventId())
            .addKeyValue("client_id", event.clientId())
            .addKeyValue("attempt", attempt)
            .addKeyValue("http_status", record.httpStatus())
            .log("Event delivered");
      }
      case PERMANENT -> fail(id, event, generation, attempt, record);
      case RETRYABLE -> {
        if (complete(event, generation, attempt, DeliveryStatus.RETRYING, record)) {
          log.atError()
              .addKeyValue("event_id", event.eventId())
              .addKeyValue("client_id", event.clientId())
              .addKeyValue("attempt", attempt)
              .addKeyValue("error", record.error())
              .log("Delivery attempt failed, will retry");
          throw RetryableDeliveryException.of(event.eventId(), attempt);
        }
      }
    }
  }

  private void fail(
      String id, TransactionEvent event, int generation, int attempt, AttemptRecord record) {
    if (complete(event, generation, attempt, DeliveryStatus.FAILED, record)) {
      log.atInfo()
          .addKeyValue("notification_event_id", id)
          .addKeyValue("event_id", event.eventId())
          .addKeyValue("client_id", event.clientId())
          .addKeyValue("attempt", attempt)
          .addKeyValue("error", record.error())
          .log("Event failed");
    }
  }

  /** Conditional write of a non-success result; counts it only if this attempt still owns it. */
  private boolean complete(
      TransactionEvent event,
      int generation,
      int attempt,
      DeliveryStatus status,
      AttemptRecord record) {
    if (!events.recordAttempt(
        event.eventId(), event.clientId(), generation, attempt, status, record)) {
      discard("stale", event.eventId(), attempt);
      return false;
    }
    count(status.name().toLowerCase(Locale.ROOT), event.clientId());
    return true;
  }

  private boolean isAllowed(String url) {
    try {
      URI uri = URI.create(url);
      String scheme = uri.getScheme();
      return uri.getHost() != null
          && ("https".equalsIgnoreCase(scheme) || (allowHttp && "http".equalsIgnoreCase(scheme)));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private AttemptRecord toRecord(String url, WebhookResult result) {
    return switch (result) {
      case WebhookResult.Responded(int status) ->
          new AttemptRecord(clock.instant(), url, status, "HTTP " + status);
      case WebhookResult.TransportError(String reason) ->
          new AttemptRecord(clock.instant(), url, null, reason);
      case WebhookResult.CircuitOpen c ->
          new AttemptRecord(clock.instant(), url, null, "Circuit open");
    };
  }

  private void discard(String reason, String eventId, int attempt) {
    meterRegistry.counter(DISCARDED, "reason", reason).increment();
    log.atDebug()
        .addKeyValue("reason", reason)
        .addKeyValue("event_id", eventId)
        .addKeyValue("attempt", attempt)
        .log("Message discarded");
  }

  /** created_at comes from the producer's clock: a skewed one could make it negative. */
  private void recordEndToEnd(TransactionEvent event) {
    Duration latency = Duration.between(event.createdAt(), clock.instant());
    meterRegistry.timer(E2E).record(latency.isNegative() ? Duration.ZERO : latency);
  }

  private void replayCount(String outcome) {
    meterRegistry.counter(REPLAYS, "outcome", outcome).increment();
  }

  private void count(String outcome, String clientId) {
    meterRegistry.counter(DELIVERIES, "outcome", outcome, "client_id", clientId).increment();
  }
}
