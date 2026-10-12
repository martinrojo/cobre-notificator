package com.cobre.manager.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cobre.manager.domain.exception.InvalidParameterException;
import com.cobre.manager.domain.exception.NotificationEventNotFoundException;
import com.cobre.manager.domain.model.DeliveryStatus;
import com.cobre.manager.domain.model.NotificationEvent;
import com.cobre.manager.domain.model.NotificationEventFilter;
import com.cobre.manager.domain.model.PageCursor;
import com.cobre.manager.domain.port.out.NotificationEventRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NotificationEventQueryServiceTest {

  private final NotificationEventRepository repository = mock(NotificationEventRepository.class);
  private final NotificationEventQueryService service =
      new NotificationEventQueryService(repository, 20, 100);

  private static final NotificationEventFilter FAILED_OF_CLIENT001 =
      new NotificationEventFilter("CLIENT001", null, null, DeliveryStatus.FAILED);

  private static final String UUID_1 = "01a1311c-4907-7a3c-9814-0adb01ff9e75";

  @Test
  void usesDefaultSizeAndAsksOneExtraRow() {
    when(repository.findAll(FAILED_OF_CLIENT001, null, 21)).thenReturn(events(3));

    var page = service.list(FAILED_OF_CLIENT001, null, null);

    assertThat(page.events()).hasSize(3);
    assertThat(page.next()).isNull();
    verify(repository).findAll(FAILED_OF_CLIENT001, null, 21);
  }

  @Test
  void acceptsMaxSize() {
    when(repository.findAll(FAILED_OF_CLIENT001, null, 101)).thenReturn(List.of());

    assertThat(service.list(FAILED_OF_CLIENT001, null, 100).events()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, 101})
  void rejectsSizeOutOfRangeWithoutQuerying(int size) {
    assertThatThrownBy(() -> service.list(FAILED_OF_CLIENT001, null, size))
        .isInstanceOf(InvalidParameterException.class)
        .hasMessage("'size' must be between 1 and 100");
    verifyNoInteractions(repository);
  }

  @Test
  void exactPageHasNoNext() {
    when(repository.findAll(FAILED_OF_CLIENT001, null, 3)).thenReturn(events(2));

    var page = service.list(FAILED_OF_CLIENT001, null, 2);

    assertThat(page.events()).hasSize(2);
    assertThat(page.next()).isNull();
  }

  @Test
  void extraRowIsDroppedAndNextPointsToLastReturned() {
    List<NotificationEvent> rows = events(3);
    when(repository.findAll(FAILED_OF_CLIENT001, null, 3)).thenReturn(rows);

    var page = service.list(FAILED_OF_CLIENT001, null, 2);

    assertThat(page.events()).containsExactly(rows.get(0), rows.get(1));
    assertThat(page.next())
        .isEqualTo(new PageCursor(rows.get(1).createdAt(), rows.get(1).id(), FAILED_OF_CLIENT001));
  }

  @Test
  void passesCursorToRepository() {
    var cursor = new PageCursor(Instant.parse("2024-03-15T09:30:22Z"), UUID_1, FAILED_OF_CLIENT001);
    when(repository.findAll(FAILED_OF_CLIENT001, cursor, 3)).thenReturn(List.of());

    service.list(FAILED_OF_CLIENT001, cursor, 2);

    verify(repository).findAll(FAILED_OF_CLIENT001, cursor, 3);
  }

  @Test
  void rejectsCursorOfAnotherClientAsInvalid() {
    var cursor =
        new PageCursor(
            Instant.parse("2024-03-15T09:30:22Z"),
            UUID_1,
            new NotificationEventFilter("CLIENT002", null, null, DeliveryStatus.FAILED));

    assertThatThrownBy(() -> service.list(FAILED_OF_CLIENT001, cursor, null))
        .isInstanceOf(InvalidParameterException.class)
        .hasMessage("'cursor' is invalid");
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsCursorWithOtherFilters() {
    var cursor = new PageCursor(Instant.parse("2024-03-15T09:30:22Z"), UUID_1, FAILED_OF_CLIENT001);
    var withoutStatus = new NotificationEventFilter("CLIENT001", null, null, null);

    assertThatThrownBy(() -> service.list(withoutStatus, cursor, null))
        .isInstanceOf(InvalidParameterException.class)
        .hasMessage("'cursor' does not match the current filters");
    verifyNoInteractions(repository);
  }

  @Test
  void acceptsCursorWithEquivalentDateInAnotherOffset() {
    var utc =
        new NotificationEventFilter(
            "CLIENT001", Instant.parse("2024-03-15T10:00:00Z"), null, DeliveryStatus.FAILED);
    var minus3 =
        new NotificationEventFilter(
            "CLIENT001",
            OffsetDateTime.parse("2024-03-15T07:00:00-03:00").toInstant(),
            null,
            DeliveryStatus.FAILED);
    var cursor = new PageCursor(Instant.parse("2024-03-15T11:00:00Z"), UUID_1, utc);
    when(repository.findAll(any(), any(), anyInt())).thenReturn(List.of());

    assertThat(service.list(minus3, cursor, null).events()).isEmpty();
  }

  private static List<NotificationEvent> events(int count) {
    return IntStream.range(0, count)
        .mapToObj(
            i ->
                new NotificationEvent(
                    "01a1311c-4907-7a3c-9814-0adb01ff9e7" + i,
                    "EVT00" + i,
                    "CLIENT001",
                    "credit_card_payment",
                    "irrelevant",
                    DeliveryStatus.FAILED,
                    Instant.parse("2024-03-15T09:30:22Z").minusSeconds(i),
                    null,
                    null,
                    0))
        .toList();
  }

  @Test
  void getsEventByClientAndId() {
    var event =
        new NotificationEvent(
            "id-EVT003",
            "EVT003",
            "CLIENT002",
            "credit_transfer",
            "irrelevant",
            DeliveryStatus.FAILED,
            Instant.parse("2024-03-15T11:20:18Z"),
            null,
            null,
            0);
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
