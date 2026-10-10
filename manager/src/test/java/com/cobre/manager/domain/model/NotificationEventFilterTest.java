package com.cobre.manager.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.manager.domain.exception.InvalidParameterException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NotificationEventFilterTest {

  private static final Instant T1 = Instant.parse("2024-03-15T10:00:00Z");
  private static final Instant T2 = Instant.parse("2024-03-15T16:00:00Z");

  @Test
  void acceptsValidRange() {
    var filter = new NotificationEventFilter("CLIENT001", T1, T2, DeliveryStatus.FAILED);

    assertThat(filter.from()).isEqualTo(T1);
    assertThat(filter.to()).isEqualTo(T2);
  }

  @Test
  void acceptsEqualBounds() {
    assertThat(new NotificationEventFilter("CLIENT001", T1, T1, null).from()).isEqualTo(T1);
  }

  @Test
  void acceptsOpenBounds() {
    assertThat(new NotificationEventFilter("CLIENT001", null, null, null).from()).isNull();
  }

  @Test
  void rejectsInvertedRange() {
    assertThatThrownBy(() -> new NotificationEventFilter("CLIENT001", T2, T1, null))
        .isInstanceOf(InvalidParameterException.class)
        .hasMessage("'from' must be before or equal to 'to'");
  }
}
