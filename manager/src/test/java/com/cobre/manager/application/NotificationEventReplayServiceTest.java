package com.cobre.manager.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

class NotificationEventReplayServiceTest {

  private static final String ID = "01a1311c-4907-7a3c-9814-0adb01ff9e75";
  private static final Instant NOW = Instant.parse("2026-10-10T12:00:00.123Z");

  private final NotificationEventRepository repository = mock(NotificationEventRepository.class);
  // Non-zero, so the test proves the generation read from the row reaches the message.
  private static final int GENERATION = 2;

  private final ReplayRequestPublisher publisher = mock(ReplayRequestPublisher.class);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final NotificationEventReplayService service =
      new NotificationEventReplayService(
          repository, publisher, Clock.fixed(NOW, ZoneOffset.UTC), meterRegistry);

  @Test
  void outcomesStartAtZero() {
    assertThat(meterRegistry.find(NotificationEventReplayService.METRIC).counters())
        .extracting(c -> c.getId().getTag("outcome") + "=" + c.count())
        .containsExactlyInAnyOrder(
            "published=0.0",
            "in_progress=0.0",
            "not_allowed=0.0",
            "not_found=0.0",
            "unavailable=0.0");
  }

  @Test
  void publishesFailedEvent() {
    givenEvent(DeliveryStatus.FAILED, null);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    Logger logger = (Logger) LoggerFactory.getLogger(NotificationEventReplayService.class);
    logs.start();
    logger.addAppender(logs);

    try {
      service.replay("CLIENT002", ID);
    } finally {
      logger.detachAppender(logs);
    }

    verify(repository).findById("CLIENT002", ID);
    // The message carries the row's event_id, never the id of the path.
    verify(publisher).publish(new ReplayRequested("EVT003", "CLIENT002", GENERATION, NOW));
    assertThat(count("published")).isEqualTo(1);
    // Ids as key-values so they land as JSON fields, filterable in Loki. client_id comes from the
    // MDC (ClientIdentificationFilter); repeating it as a key-value makes the ECS formatter drop
    // the
    // line.
    assertThat(logs.list)
        .filteredOn(e -> e.getMessage().equals("Replay requested"))
        .singleElement()
        .extracting(ILoggingEvent::getKeyValuePairs)
        .asInstanceOf(InstanceOfAssertFactories.list(KeyValuePair.class))
        .extracting(kv -> kv.key + "=" + kv.value)
        .containsExactly("notification_event_id=" + ID, "event_id=EVT003");
  }

  @ParameterizedTest
  @EnumSource(
      value = DeliveryStatus.class,
      names = {"PENDING", "RETRYING"})
  void doesNotPublishWhenReplayInProgress(DeliveryStatus status) {
    givenEvent(status, NOW);

    service.replay("CLIENT002", ID);

    verify(publisher, never()).publish(any());
    assertThat(count("in_progress")).isEqualTo(1);
  }

  @Test
  void rejectsDeliveredEvent() {
    givenEvent(DeliveryStatus.DELIVERED, null);

    assertThatThrownBy(() -> service.replay("CLIENT002", ID))
        .isInstanceOf(ReplayNotAllowedException.class)
        .hasMessageContaining("DELIVERED");
    verify(publisher, never()).publish(any());
    assertThat(count("not_allowed")).isEqualTo(1);
  }

  @Test
  void notFoundWhenMissingOrOwnedByAnotherClient() {
    when(repository.findById("CLIENT001", ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.replay("CLIENT001", ID))
        .isInstanceOf(NotificationEventNotFoundException.class);
    verify(publisher, never()).publish(any());
    assertThat(count("not_found")).isEqualTo(1);
  }

  @Test
  void propagatesPublishFailure() {
    givenEvent(DeliveryStatus.FAILED, null);
    var failure = ReplayPublishException.of("EVT003", new RuntimeException("broker down"));
    doThrow(failure).when(publisher).publish(any());

    assertThatThrownBy(() -> service.replay("CLIENT002", ID)).isSameAs(failure);
    assertThat(count("unavailable")).isEqualTo(1);
    assertThat(count("published")).isZero();
  }

  private void givenEvent(DeliveryStatus status, Instant lastReplayAt) {
    when(repository.findById("CLIENT002", ID))
        .thenReturn(
            Optional.of(
                new NotificationEvent(
                    ID,
                    "EVT003",
                    "CLIENT002",
                    "credit_transfer",
                    "content",
                    status,
                    Instant.parse("2024-03-15T11:20:18Z"),
                    null,
                    lastReplayAt,
                    GENERATION)));
  }

  private double count(String outcome) {
    var counter =
        meterRegistry.find(NotificationEventReplayService.METRIC).tag("outcome", outcome).counter();
    return counter == null ? 0 : counter.count();
  }
}
