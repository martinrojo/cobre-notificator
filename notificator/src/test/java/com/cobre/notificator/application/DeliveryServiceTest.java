package com.cobre.notificator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cobre.notificator.config.DeliveryProperties;
import com.cobre.notificator.domain.exception.RetryableDeliveryException;
import com.cobre.notificator.domain.model.AttemptRecord;
import com.cobre.notificator.domain.model.DeliveryStatus;
import com.cobre.notificator.domain.model.NotificationEvent;
import com.cobre.notificator.domain.model.ReplayRequest;
import com.cobre.notificator.domain.model.Subscription;
import com.cobre.notificator.domain.model.TransactionEvent;
import com.cobre.notificator.domain.model.WebhookResult;
import com.cobre.notificator.domain.port.out.NotificationEventRepository;
import com.cobre.notificator.domain.port.out.SubscriptionRepository;
import com.cobre.notificator.domain.port.out.WebhookClient;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

class DeliveryServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-11T10:00:00Z");
  private static final String CONTENT = "Deposit of $2,500.00 from Employer XYZ";
  private static final String URL = "https://a.example/hook";
  private static final String INSERTED_ID = "01a1311c-4907-792c-a433-4e7984227e77";
  private static final String ROW_ID = "01a1311c-4907-7a00-b41c-d01d8e4de129";
  private static final TransactionEvent EVENT =
      new TransactionEvent("EVT100", "CLIENT001", "credit_deposit", CONTENT, NOW);

  private final NotificationEventRepository events = mock(NotificationEventRepository.class);
  private final SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
  private final WebhookClient webhook = mock(WebhookClient.class);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger(DeliveryService.class);
  private DeliveryService service;

  @BeforeEach
  void setUp() {
    service = service(false);
    logs.start();
    logger.addAppender(logs);
    logger.setLevel(Level.DEBUG);
  }

  @AfterEach
  void tearDown() {
    // Captured at DEBUG too: the content must never reach any log line.
    assertThat(logs.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .noneMatch(m -> m.contains(CONTENT));
    assertThat(logs.list)
        .flatExtracting(e -> e.getKeyValuePairs() == null ? List.of() : e.getKeyValuePairs())
        .noneMatch(kv -> String.valueOf(((KeyValuePair) kv).value).contains(CONTENT));
    logger.detachAppender(logs);
  }

  @Test
  void knownCountersExistAtZeroFromStartup() {
    for (String reason : List.of("duplicate", "stale", "dlt_ignored")) {
      assertThat(meterRegistry.find(DeliveryService.DISCARDED).tag("reason", reason).counter())
          .isNotNull()
          .extracting(c -> c.count())
          .isEqualTo(0.0);
    }
    for (String outcome : List.of("started", "discarded")) {
      assertThat(meterRegistry.find(DeliveryService.REPLAYS).tag("outcome", outcome).counter())
          .isNotNull()
          .extracting(c -> c.count())
          .isEqualTo(0.0);
    }
  }

  @Test
  void duplicateIsDiscardedWithoutPost() {
    when(events.insertClaim(EVENT)).thenReturn(Optional.empty());

    service.first(EVENT);

    verify(webhook, never()).post(anyString(), anyString(), any(), anyInt());
    assertThat(discarded("duplicate")).isEqualTo(1);
  }

  @Test
  void staleRetryIsDiscardedWithoutPost() {
    when(events.claimAttempt("EVT100", "CLIENT001", 0, 2)).thenReturn(false);
    when(events.findById("EVT100", "CLIENT001"))
        .thenReturn(Optional.of(row(DeliveryStatus.DELIVERED, 2)));

    service.retry("EVT100", "CLIENT001", 0, 2);

    verify(webhook, never()).post(anyString(), anyString(), any(), anyInt());
    assertThat(discarded("stale")).isEqualTo(1);
  }

  @Test
  void notSubscribedWithoutPost() {
    claimFirst();
    when(subscriptions.findActive("CLIENT001", "credit_deposit")).thenReturn(Optional.empty());
    when(events.recordAttempt(
            eq("EVT100"), eq("CLIENT001"), eq(0), eq(1), eq(DeliveryStatus.NOT_SUBSCRIBED), any()))
        .thenReturn(true);

    service.first(EVENT);

    verify(webhook, never()).post(anyString(), anyString(), any(), anyInt());
    assertThat(deliveries("not_subscribed")).isEqualTo(1);
  }

  @Test
  void httpUrlNotAllowedFailsWithoutPost() {
    claimFirst();
    subscribed("http://a.example/hook");
    when(events.recordAttempt(
            eq("EVT100"), eq("CLIENT001"), eq(0), eq(1), eq(DeliveryStatus.FAILED), any()))
        .thenReturn(true);

    service.first(EVENT);

    verify(webhook, never()).post(anyString(), anyString(), any(), anyInt());
    assertThat(deliveries("failed")).isEqualTo(1);
  }

  @Test
  void httpUrlAllowedLocally() {
    service = service(true);
    claimFirst();
    subscribed("http://a.example/hook");
    when(webhook.post("http://a.example/hook", INSERTED_ID, EVENT, 1))
        .thenReturn(new WebhookResult.Responded(200));
    when(events.markDelivered(eq("EVT100"), eq("CLIENT001"), eq(0), any())).thenReturn(true);

    service.first(EVENT);

    assertThat(deliveries("delivered")).isEqualTo(1);
  }

  @Test
  void successMarksDelivered() {
    claimFirst();
    subscribed(URL);
    when(webhook.post(URL, INSERTED_ID, EVENT, 1)).thenReturn(new WebhookResult.Responded(204));
    when(events.markDelivered(eq("EVT100"), eq("CLIENT001"), eq(0), any())).thenReturn(true);

    service.first(EVENT);

    // The first attempt sends the id generated by the claim.
    verify(webhook).post(URL, INSERTED_ID, EVENT, 1);
    ArgumentCaptor<AttemptRecord> record = ArgumentCaptor.forClass(AttemptRecord.class);
    verify(events).markDelivered(eq("EVT100"), eq("CLIENT001"), eq(0), record.capture());
    assertThat(record.getValue()).isEqualTo(new AttemptRecord(NOW, URL, 204, "HTTP 204"));
    assertThat(deliveries("delivered")).isEqualTo(1);
    // Ids as key-values so they land as JSON fields, filterable in Loki.
    ILoggingEvent delivered =
        logs.list.stream()
            .filter(e -> e.getMessage().equals("Event delivered"))
            .findFirst()
            .orElseThrow();
    assertThat(delivered.getKeyValuePairs())
        .extracting(kv -> kv.key + "=" + kv.value)
        .contains(
            "notification_event_id=" + INSERTED_ID,
            "event_id=EVT100",
            "client_id=CLIENT001",
            "attempt=1",
            "http_status=204");
  }

  @Test
  void retryableWithClaimThrows() {
    claimFirst();
    subscribed(URL);
    when(webhook.post(URL, INSERTED_ID, EVENT, 1)).thenReturn(new WebhookResult.Responded(503));
    when(events.recordAttempt(
            eq("EVT100"), eq("CLIENT001"), eq(0), eq(1), eq(DeliveryStatus.RETRYING), any()))
        .thenReturn(true);

    assertThatThrownBy(() -> service.first(EVENT)).isInstanceOf(RetryableDeliveryException.class);
    assertThat(deliveries("retrying")).isEqualTo(1);
  }

  @Test
  void circuitOpenIsRetriedWithoutStatus() {
    claimFirst();
    subscribed(URL);
    when(webhook.post(URL, INSERTED_ID, EVENT, 1)).thenReturn(new WebhookResult.CircuitOpen());
    ArgumentCaptor<AttemptRecord> record = ArgumentCaptor.forClass(AttemptRecord.class);
    when(events.recordAttempt(
            eq("EVT100"),
            eq("CLIENT001"),
            eq(0),
            eq(1),
            eq(DeliveryStatus.RETRYING),
            record.capture()))
        .thenReturn(true);

    assertThatThrownBy(() -> service.first(EVENT)).isInstanceOf(RetryableDeliveryException.class);
    assertThat(record.getValue()).isEqualTo(new AttemptRecord(NOW, URL, null, "Circuit open"));
    assertThat(deliveries("retrying")).isEqualTo(1);
  }

  @Test
  void retryableWithLostClaimDoesNotThrow() {
    claimFirst();
    subscribed(URL);
    when(webhook.post(URL, INSERTED_ID, EVENT, 1))
        .thenReturn(new WebhookResult.TransportError("timeout"));
    when(events.recordAttempt(
            eq("EVT100"), eq("CLIENT001"), eq(0), eq(1), eq(DeliveryStatus.RETRYING), any()))
        .thenReturn(false);

    service.first(EVENT);

    assertThat(discarded("stale")).isEqualTo(1);
    assertThat(deliveries("retrying")).isZero();
  }

  @Test
  void permanentErrorFails() {
    claimFirst();
    subscribed(URL);
    when(webhook.post(URL, INSERTED_ID, EVENT, 1)).thenReturn(new WebhookResult.Responded(400));
    when(events.recordAttempt(
            eq("EVT100"), eq("CLIENT001"), eq(0), eq(1), eq(DeliveryStatus.FAILED), any()))
        .thenReturn(true);

    service.first(EVENT);

    assertThat(deliveries("failed")).isEqualTo(1);
  }

  @Test
  void retryUsesClientIdOfTheRowAndClaimedAttempt() {
    when(events.claimAttempt("EVT100", "CLIENT001", 0, 3)).thenReturn(true);
    when(events.findById("EVT100", "CLIENT001"))
        .thenReturn(Optional.of(row(DeliveryStatus.RETRYING, 3)));
    subscribed(URL);
    when(webhook.post(URL, ROW_ID, EVENT, 3)).thenReturn(new WebhookResult.Responded(200));
    when(events.markDelivered(eq("EVT100"), eq("CLIENT001"), eq(0), any())).thenReturn(true);

    service.retry("EVT100", "CLIENT001", 0, 3);

    verify(subscriptions).findActive("CLIENT001", "credit_deposit");
    verify(webhook).post(URL, ROW_ID, EVENT, 3);
  }

  @Test
  void replayDeliversAttemptOneOfTheNextGenerationFromTheRow() {
    // The message only names the event: the delivery data must come from the row.
    ReplayRequest request = new ReplayRequest("EVT100", "CLIENT001", 1, NOW);
    when(events.claimReplay(request)).thenReturn(true);
    when(events.findById("EVT100", "CLIENT001"))
        .thenReturn(Optional.of(row(DeliveryStatus.PENDING, 2, 1)));
    subscribed(URL);
    when(webhook.post(URL, ROW_ID, EVENT, 1)).thenReturn(new WebhookResult.Responded(200));
    when(events.markDelivered(eq("EVT100"), eq("CLIENT001"), eq(2), any())).thenReturn(true);

    service.replay(request);

    verify(webhook).post(URL, ROW_ID, EVENT, 1);
    verify(events).markDelivered(eq("EVT100"), eq("CLIENT001"), eq(2), any());
    assertThat(replays("started")).isEqualTo(1);
    assertThat(deliveries("delivered")).isEqualTo(1);
    assertThat(logs.list)
        .filteredOn(e -> e.getMessage().equals("Replay started"))
        .singleElement()
        .extracting(ILoggingEvent::getKeyValuePairs)
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(KeyValuePair.class))
        .extracting(kv -> kv.key + "=" + kv.value)
        .contains(
            "notification_event_id=" + ROW_ID,
            "event_id=EVT100",
            "client_id=CLIENT001",
            "generation=2");
  }

  @Test
  void replayThatLosesTheClaimIsDiscardedWithoutPost() {
    ReplayRequest request = new ReplayRequest("EVT100", "CLIENT002", 0, NOW);
    when(events.claimReplay(request)).thenReturn(false);

    service.replay(request);

    verify(webhook, never()).post(anyString(), anyString(), any(), anyInt());
    verify(events, never()).findById(anyString(), anyString());
    assertThat(replays("discarded")).isEqualTo(1);
    assertThat(replays("started")).isZero();
  }

  @Test
  void retryableReplayAttemptThrowsWithItsGeneration() {
    ReplayRequest request = new ReplayRequest("EVT100", "CLIENT001", 0, NOW);
    when(events.claimReplay(request)).thenReturn(true);
    when(events.findById("EVT100", "CLIENT001"))
        .thenReturn(Optional.of(row(DeliveryStatus.PENDING, 1, 1)));
    subscribed(URL);
    when(webhook.post(URL, ROW_ID, EVENT, 1)).thenReturn(new WebhookResult.Responded(503));
    when(events.recordAttempt(
            eq("EVT100"), eq("CLIENT001"), eq(1), eq(1), eq(DeliveryStatus.RETRYING), any()))
        .thenReturn(true);

    assertThatThrownBy(() -> service.replay(request))
        .isInstanceOf(RetryableDeliveryException.class);
  }

  @Test
  void retryOfAnotherGenerationIsDiscardedWithoutPost() {
    when(events.claimAttempt("EVT100", "CLIENT001", 0, 2)).thenReturn(false);
    when(events.findById("EVT100", "CLIENT001"))
        .thenReturn(Optional.of(row(DeliveryStatus.PENDING, 1, 1)));

    service.retry("EVT100", "CLIENT001", 0, 2);

    verify(webhook, never()).post(anyString(), anyString(), any(), anyInt());
    assertThat(discarded("stale")).isEqualTo(1);
  }

  @Test
  void lateSuccessOfAnotherGenerationIsDiscarded() {
    when(events.claimAttempt("EVT100", "CLIENT001", 0, 2)).thenReturn(true);
    when(events.findById("EVT100", "CLIENT001"))
        .thenReturn(Optional.of(row(DeliveryStatus.RETRYING, 0, 2)));
    subscribed(URL);
    when(webhook.post(URL, ROW_ID, EVENT, 2)).thenReturn(new WebhookResult.Responded(200));
    // A replay moved the row to generation 1 while the POST was in flight.
    when(events.markDelivered(eq("EVT100"), eq("CLIENT001"), eq(0), any())).thenReturn(false);

    service.retry("EVT100", "CLIENT001", 0, 2);

    assertThat(discarded("stale")).isEqualTo(1);
    assertThat(deliveries("delivered")).isZero();
    assertThat(endToEnd()).isNull();
  }

  @Test
  void deliveryOfFirstGenerationRecordsEndToEndLatency() {
    TransactionEvent event = eventCreatedAt(NOW.minusSeconds(90));
    deliveredOnFirstAttempt(event);

    service.first(event);

    assertThat(endToEnd().count()).isEqualTo(1);
    assertThat(endToEnd().totalTime(TimeUnit.SECONDS)).isEqualTo(90);
  }

  @Test
  void createdAtInTheFutureRecordsZero() {
    TransactionEvent event = eventCreatedAt(NOW.plusSeconds(30));
    deliveredOnFirstAttempt(event);

    service.first(event);

    assertThat(endToEnd().count()).isEqualTo(1);
    assertThat(endToEnd().totalTime(TimeUnit.SECONDS)).isZero();
  }

  @Test
  void replayDeliveryDoesNotRecordEndToEndLatency() {
    ReplayRequest request = new ReplayRequest("EVT100", "CLIENT001", 1, NOW);
    when(events.claimReplay(request)).thenReturn(true);
    when(events.findById("EVT100", "CLIENT001"))
        .thenReturn(Optional.of(row(DeliveryStatus.PENDING, 2, 1)));
    subscribed(URL);
    when(webhook.post(URL, ROW_ID, EVENT, 1)).thenReturn(new WebhookResult.Responded(200));
    when(events.markDelivered(eq("EVT100"), eq("CLIENT001"), eq(2), any())).thenReturn(true);

    service.replay(request);

    assertThat(deliveries("delivered")).isEqualTo(1);
    assertThat(endToEnd()).isNull();
  }

  private DeliveryService service(boolean allowHttp) {
    DeliveryProperties properties =
        new DeliveryProperties(
            null,
            null,
            new DeliveryProperties.Webhook(Duration.ofSeconds(1), Duration.ofSeconds(1), allowHttp),
            null);
    return new DeliveryService(
        events,
        subscriptions,
        webhook,
        Clock.fixed(NOW, ZoneOffset.UTC),
        meterRegistry,
        properties);
  }

  private static TransactionEvent eventCreatedAt(Instant createdAt) {
    return new TransactionEvent("EVT100", "CLIENT001", "credit_deposit", CONTENT, createdAt);
  }

  private void deliveredOnFirstAttempt(TransactionEvent event) {
    when(events.insertClaim(event)).thenReturn(Optional.of(INSERTED_ID));
    subscribed(URL);
    when(webhook.post(URL, INSERTED_ID, event, 1)).thenReturn(new WebhookResult.Responded(200));
    when(events.markDelivered(eq("EVT100"), eq("CLIENT001"), eq(0), any())).thenReturn(true);
  }

  private Timer endToEnd() {
    return meterRegistry.find(DeliveryService.E2E).timer();
  }

  private void claimFirst() {
    when(events.insertClaim(EVENT)).thenReturn(Optional.of(INSERTED_ID));
  }

  private void subscribed(String url) {
    when(subscriptions.findActive("CLIENT001", "credit_deposit"))
        .thenReturn(Optional.of(new Subscription("CLIENT001", "credit_deposit", url)));
  }

  private static NotificationEvent row(DeliveryStatus status, int attempts) {
    return row(status, 0, attempts);
  }

  private static NotificationEvent row(DeliveryStatus status, int generation, int attempts) {
    return new NotificationEvent(
        ROW_ID,
        "EVT100",
        "CLIENT001",
        "credit_deposit",
        CONTENT,
        status,
        attempts,
        NOW,
        generation,
        generation == 0 ? null : NOW);
  }

  private double replays(String outcome) {
    return meterRegistry.counter(DeliveryService.REPLAYS, "outcome", outcome).count();
  }

  private double deliveries(String outcome) {
    return meterRegistry
        .counter(DeliveryService.DELIVERIES, "outcome", outcome, "client_id", "CLIENT001")
        .count();
  }

  private double discarded(String reason) {
    return meterRegistry.counter(DeliveryService.DISCARDED, "reason", reason).count();
  }
}
