package com.cobre.notificator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cobre.notificator.config.DeliveryProperties;
import com.cobre.notificator.domain.model.DeliveryStatus;
import com.cobre.notificator.domain.model.NotificationEvent;
import com.cobre.notificator.domain.port.out.NotificationEventRepository;
import com.cobre.notificator.domain.port.out.RetryPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class StaleDeliveryRecoveryServiceTest {

  private static final Duration LEASE = Duration.ofSeconds(120);

  private final NotificationEventRepository events = mock(NotificationEventRepository.class);
  private final RetryPublisher publisher = mock(RetryPublisher.class);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final StaleDeliveryRecoveryService service =
      new StaleDeliveryRecoveryService(
          events,
          publisher,
          meterRegistry,
          new DeliveryProperties(
              new DeliveryProperties.Retry(4, Duration.ofSeconds(60), 5),
              LEASE,
              null,
              new DeliveryProperties.Recovery(Duration.ofSeconds(60), 100)));

  @Test
  void knownCountersExistAtZeroFromStartup() {
    for (String action : List.of("republished", "failed")) {
      assertThat(
              meterRegistry
                  .find(StaleDeliveryRecoveryService.RECOVERY_EVENTS)
                  .tag("action", action)
                  .counter())
          .isNotNull()
          .extracting(c -> c.count())
          .isEqualTo(0.0);
    }
  }

  @Test
  void firstAttemptRepublishesToRetry0() {
    stale(event("EVT1", 1));
    when(events.refreshIfStale("EVT1", "CLIENT001", 0, 1, Duration.ofSeconds(60).plus(LEASE)))
        .thenReturn(true);

    service.recover();

    verify(publisher).publish(event("EVT1", 1), 1);
    assertThat(count("republished")).isEqualTo(1);
  }

  @Test
  void secondAttemptRepublishesToRetry1() {
    stale(event("EVT2", 2));
    when(events.refreshIfStale("EVT2", "CLIENT001", 0, 2, Duration.ofSeconds(300).plus(LEASE)))
        .thenReturn(true);

    service.recover();

    verify(publisher).publish(event("EVT2", 2), 2);
  }

  @Test
  void lastAttemptFailsWithoutPublishing() {
    stale(event("EVT4", 4));
    when(events.failIfStale("EVT4", "CLIENT001", 0, 4, LEASE)).thenReturn(true);

    service.recover();

    verify(events, never()).refreshIfStale(any(), any(), anyInt(), anyInt(), any());
    verify(publisher, never()).publish(any(), anyInt());
    assertThat(count("failed")).isEqualTo(1);
    assertThat(failedDeliveries()).isEqualTo(1);
  }

  @Test
  void lostFailIsNotCounted() {
    stale(event("EVT4", 4));
    when(events.failIfStale("EVT4", "CLIENT001", 0, 4, LEASE)).thenReturn(false);

    service.recover();

    assertThat(count("failed")).isZero();
    assertThat(failedDeliveries()).isZero();
  }

  @Test
  void lostRefreshDoesNotPublish() {
    stale(event("EVT1", 1));
    when(events.refreshIfStale(eq("EVT1"), eq("CLIENT001"), eq(0), eq(1), any())).thenReturn(false);

    service.recover();

    verify(publisher, never()).publish(any(), anyInt());
  }

  @Test
  void publishFailureDoesNotStopTheBatch() {
    stale(event("EVT1", 1), event("EVT2", 2));
    when(events.refreshIfStale(any(), any(), anyInt(), anyInt(), any())).thenReturn(true);
    doThrow(new IllegalStateException("broker down")).when(publisher).publish(event("EVT1", 1), 1);

    service.recover();

    verify(publisher).publish(event("EVT2", 2), 2);
    assertThat(count("republished")).isEqualTo(1);
  }

  @Test
  void replayRowIsRefreshedAndRepublishedWithItsGeneration() {
    NotificationEvent row = event("EVT3", 1, 1);
    stale(row);
    when(events.refreshIfStale("EVT3", "CLIENT001", 1, 1, Duration.ofSeconds(60).plus(LEASE)))
        .thenReturn(true);

    service.recover();

    // The publisher picks the replay format from the row's generation.
    verify(publisher).publish(row, 1);
  }

  @Test
  void replayRowLastAttemptFailsOnlyItsGeneration() {
    stale(event("EVT4", 2, 4));
    when(events.failIfStale("EVT4", "CLIENT001", 2, 4, LEASE)).thenReturn(true);

    service.recover();

    assertThat(count("failed")).isEqualTo(1);
  }

  private void stale(NotificationEvent... rows) {
    when(events.findStale(LEASE, 100)).thenReturn(List.of(rows));
  }

  private static NotificationEvent event(String eventId, int attempts) {
    return event(eventId, 0, attempts);
  }

  private static NotificationEvent event(String eventId, int generation, int attempts) {
    return new NotificationEvent(
        "id-" + eventId,
        eventId,
        "CLIENT001",
        "credit_deposit",
        "{}",
        attempts == 1 ? DeliveryStatus.PENDING : DeliveryStatus.RETRYING,
        attempts,
        Instant.parse("2026-10-11T10:00:00Z"),
        generation,
        generation == 0 ? null : Instant.parse("2026-10-11T11:00:00Z"));
  }

  private double failedDeliveries() {
    return meterRegistry
        .counter(DeliveryService.DELIVERIES, "outcome", "failed", "client_id", "CLIENT001")
        .count();
  }

  private double count(String action) {
    return meterRegistry
        .counter(StaleDeliveryRecoveryService.RECOVERY_EVENTS, "action", action)
        .count();
  }
}
