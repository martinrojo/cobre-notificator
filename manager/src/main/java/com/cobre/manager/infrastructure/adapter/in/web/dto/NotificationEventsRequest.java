package com.cobre.manager.infrastructure.adapter.in.web.dto;

import com.cobre.manager.domain.exception.InvalidParameterException;
import com.cobre.manager.domain.model.DeliveryStatus;
import com.cobre.manager.domain.model.NotificationEventFilter;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import org.springframework.web.bind.annotation.BindParam;

public record NotificationEventsRequest(
    String from, String to, @BindParam("delivery_status") DeliveryStatus deliveryStatus) {

  public NotificationEventFilter toFilter(String clientId) {
    return new NotificationEventFilter(
        clientId, parse("from", from), parse("to", to), deliveryStatus);
  }

  private static Instant parse(String param, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value.strip()).toInstant();
    } catch (DateTimeParseException e) {
      throw InvalidParameterException.invalidDate(param);
    }
  }
}
