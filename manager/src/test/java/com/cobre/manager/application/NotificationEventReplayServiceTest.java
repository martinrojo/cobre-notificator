package com.cobre.manager.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cobre.manager.domain.exception.NotificationEventNotFoundException;
import com.cobre.manager.domain.exception.ReplayNotAllowedException;
import com.cobre.manager.domain.exception.ReplayPublishException;
import com.cobre.manager.domain.model.DeliveryStatus;
import com.cobre.manager.domain.model.NotificationEvent;
import com.cobre.manager.domain.model.ReplayRequested;
import com.cobre.manager.domain.port.out.NotificationEventRepository;
import com.cobre.manager.domain.port.out.ReplayRequestPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class NotificationEventReplayServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-10T12:00:00.123Z");

  private final NotificationEventRepository repository = mock(NotificationEventRepository.class);
  private final ReplayRequestPublisher publisher = mock(ReplayRequestPublisher.class);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final NotificationEventReplayService service =
      new NotificationEventReplayService(
          repository, publisher, Clock.fixed(NOW, ZoneOffset.UTC), meterRegistry);

  @Test
  void publishesFailedEvent() {
    givenEvent(DeliveryStatus.FAILED, null);

    service.replay("CLIENT002", "EVT003");

    verify(repository).findById("CLIENT002", "EVT003");
    verify(publisher).publish(new ReplayRequested("EVT003", "CLIENT002", NOW));
    assertThat(count("published")).isEqualTo(1);
  }

  @ParameterizedTest
  @EnumSource(
      value = DeliveryStatus.class,
      names = {"PENDING", "RETRYING"})
  void doesNotPublishWhenReplayInProgress(DeliveryStatus status) {
    givenEvent(status, NOW);

    service.replay("CLIENT002", "EVT003");

    verify(publisher, never()).publish(any());
    assertThat(count("in_progress")).isEqualTo(1);
  }

  @Test
  void rejectsDeliveredEvent() {
    givenEvent(DeliveryStatus.DELIVERED, null);

    assertThatThrownBy(() -> service.replay("CLIENT002", "EVT003"))
        .isInstanceOf(ReplayNotAllowedException.class)
        .hasMessageContaining("DELIVERED");
    verify(publisher, never()).publish(any());
    assertThat(count("not_allowed")).isEqualTo(1);
  }

  @Test
  void notFoundWhenMissingOrOwnedByAnotherClient() {
    when(repository.findById("CLIENT001", "EVT003")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.replay("CLIENT001", "EVT003"))
        .isInstanceOf(NotificationEventNotFoundException.class);
    verify(publisher, never()).publish(any());
    assertThat(count("not_found")).isEqualTo(1);
  }

  @Test
  void propagatesPublishFailure() {
    givenEvent(DeliveryStatus.FAILED, null);
    var failure = ReplayPublishException.of("EVT003", new RuntimeException("broker down"));
    doThrow(failure).when(publisher).publish(any());

    assertThatThrownBy(() -> service.replay("CLIENT002", "EVT003")).isSameAs(failure);
    assertThat(count("unavailable")).isEqualTo(1);
    assertThat(count("published")).isZero();
  }

  private void givenEvent(DeliveryStatus status, Instant lastReplayAt) {
    when(repository.findById("CLIENT002", "EVT003"))
        .thenReturn(
            Optional.of(
                new NotificationEvent(
                    "EVT003",
                    "CLIENT002",
                    "credit_transfer",
                    "content",
                    status,
                    Instant.parse("2024-03-15T11:20:18Z"),
                    null,
                    lastReplayAt)));
  }

  private double count(String outcome) {
    var counter =
        meterRegistry.find(NotificationEventReplayService.METRIC).tag("outcome", outcome).counter();
    return counter == null ? 0 : counter.count();
  }
}
