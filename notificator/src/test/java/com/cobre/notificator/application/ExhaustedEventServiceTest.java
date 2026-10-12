package com.cobre.notificator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cobre.notificator.domain.model.DeliveryStatus;
import com.cobre.notificator.domain.port.out.NotificationEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class ExhaustedEventServiceTest {

  private final NotificationEventRepository events = mock(NotificationEventRepository.class);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final ExhaustedEventService service = new ExhaustedEventService(events, meterRegistry);

  @Test
  void failsCurrentAttempt() {
    when(events.recordAttempt("EVT100", "CLIENT001", 0, 4, DeliveryStatus.FAILED, null))
        .thenReturn(true);

    service.failExhausted("EVT100", "CLIENT001", 0, 4);

    assertThat(failed()).isEqualTo(1);
    assertThat(ignored()).isZero();
  }

  @Test
  void ignoresFinalOrMissingEvent() {
    // DELIVERED, another attempt or no row: the conditional write matches nothing.
    when(events.recordAttempt("EVT100", "CLIENT001", 0, 4, DeliveryStatus.FAILED, null))
        .thenReturn(false);

    service.failExhausted("EVT100", "CLIENT001", 0, 4);

    assertThat(failed()).isZero();
    assertThat(ignored()).isEqualTo(1);
  }

  @Test
  void failsOnlyTheGenerationOfTheMessage() {
    when(events.recordAttempt("EVT100", "CLIENT001", 1, 4, DeliveryStatus.FAILED, null))
        .thenReturn(false);

    service.failExhausted("EVT100", "CLIENT001", 1, 4);

    verify(events).recordAttempt("EVT100", "CLIENT001", 1, 4, DeliveryStatus.FAILED, null);
    assertThat(failed()).isZero();
  }

  private double ignored() {
    return meterRegistry.counter(DeliveryService.DISCARDED, "reason", "dlt_ignored").count();
  }

  private double failed() {
    return meterRegistry
        .counter(DeliveryService.DELIVERIES, "outcome", "failed", "client_id", "CLIENT001")
        .count();
  }
}
