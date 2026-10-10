package com.cobre.manager.domain.exception;

import com.cobre.manager.domain.model.DeliveryStatus;

public final class ReplayNotAllowedException extends RuntimeException {

  private final String eventId;

  private ReplayNotAllowedException(String eventId, DeliveryStatus status) {
    super("Notification event cannot be replayed in status " + status);
    this.eventId = eventId;
  }

  public static ReplayNotAllowedException of(String eventId, DeliveryStatus status) {
    return new ReplayNotAllowedException(eventId, status);
  }

  public String eventId() {
    return eventId;
  }
}
