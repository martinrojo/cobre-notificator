package com.cobre.manager.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cobre.manager.domain.exception.NotificationEventNotFoundException;
import com.cobre.manager.domain.model.DeliveryStatus;
import com.cobre.manager.domain.model.NotificationEvent;
import com.cobre.manager.domain.model.NotificationEventFilter;
import com.cobre.manager.domain.port.out.NotificationEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NotificationEventQueryServiceTest {

  private final NotificationEventRepository repository = mock(NotificationEventRepository.class);
  private final NotificationEventQueryService service =
      new NotificationEventQueryService(repository);

  @Test
  void delegatesFilterToRepository() {
    var filter = new NotificationEventFilter("CLIENT001", null, null, DeliveryStatus.FAILED);
    var event =
        new NotificationEvent(
            "EVT001",
            "CLIENT001",
            "credit_card_payment",
            "irrelevant",
            DeliveryStatus.FAILED,
            Instant.parse("2024-03-15T09:30:22Z"),
            null,
            null);
    when(repository.findAll(filter)).thenReturn(List.of(event));

    assertThat(service.list(filter)).containsExactly(event);
    verify(repository).findAll(filter);
  }

  @Test
  void getsEventByClientAndId() {
    var event =
        new NotificationEvent(
            "EVT003",
            "CLIENT002",
            "credit_transfer",
            "irrelevant",
            DeliveryStatus.FAILED,
            Instant.parse("2024-03-15T11:20:18Z"),
            null,
            null);
    when(repository.findById("CLIENT002", "EVT003")).thenReturn(Optional.of(event));

    assertThat(service.get("CLIENT002", "EVT003")).isEqualTo(event);
    verify(repository).findById("CLIENT002", "EVT003");
  }

  @Test
  void throwsNotFoundWhenRepositoryReturnsEmpty() {
    when(repository.findById("CLIENT001", "EVT003")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.get("CLIENT001", "EVT003"))
        .isInstanceOf(NotificationEventNotFoundException.class)
        .hasMessage("Notification event not found");
  }
}
