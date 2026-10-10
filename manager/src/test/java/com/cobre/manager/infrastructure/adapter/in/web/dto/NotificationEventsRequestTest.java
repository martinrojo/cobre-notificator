package com.cobre.manager.infrastructure.adapter.in.web.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.manager.domain.exception.InvalidParameterException;
import com.cobre.manager.domain.model.DeliveryStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NotificationEventsRequestTest {

  @Test
  void mapsToFilter() {
    var filter =
        new NotificationEventsRequest(
                "2024-03-15T10:00:00Z", "2024-03-15T18:00:00+02:00", DeliveryStatus.FAILED)
            .toFilter("CLIENT001");

    assertThat(filter.clientId()).isEqualTo("CLIENT001");
    assertThat(filter.from()).isEqualTo(Instant.parse("2024-03-15T10:00:00Z"));
    assertThat(filter.to()).isEqualTo(Instant.parse("2024-03-15T16:00:00Z"));
    assertThat(filter.deliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
  }

  @Test
  void nullParamsMeanNoFilter() {
    var filter = new NotificationEventsRequest(null, null, null).toFilter("CLIENT001");

    assertThat(filter.from()).isNull();
    assertThat(filter.to()).isNull();
    assertThat(filter.deliveryStatus()).isNull();
  }

  @Test
  void rejectsMalformedDate() {
    assertThatThrownBy(
            () -> new NotificationEventsRequest("15-03-2024", null, null).toFilter("CLIENT001"))
        .isInstanceOf(InvalidParameterException.class)
        .hasMessageStartingWith("'from' must be an ISO-8601");
  }

  @Test
  void rejectsDateWithoutOffset() {
    assertThatThrownBy(
            () ->
                new NotificationEventsRequest(null, "2024-03-15T10:00:00", null)
                    .toFilter("CLIENT001"))
        .isInstanceOf(InvalidParameterException.class)
        .hasMessageStartingWith("'to' must be an ISO-8601");
  }
}
