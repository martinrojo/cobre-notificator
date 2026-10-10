package com.cobre.manager.domain.model;

import com.cobre.manager.domain.exception.InvalidParameterException;
import java.time.Instant;
import java.util.Objects;

public record NotificationEventFilter(
    String clientId, Instant from, Instant to, DeliveryStatus deliveryStatus) {

  public NotificationEventFilter {
    Objects.requireNonNull(clientId, "clientId");
    if (from != null && to != null && from.isAfter(to)) {
      throw InvalidParameterException.invalidRange();
    }
  }
}
