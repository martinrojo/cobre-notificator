package com.cobre.manager.infrastructure.adapter.in.web.dto;

import com.cobre.manager.domain.model.DeliveryStatus;
import com.cobre.manager.domain.model.NotificationEvent;
import java.time.Instant;

public record NotificationEventResponse(
    String notificationEventId,
    String eventId,
    String eventType,
    String content,
    DeliveryStatus deliveryStatus,
    Instant createdAt,
    Instant deliveryDate) {

  public static NotificationEventResponse from(NotificationEvent event) {
    return new NotificationEventResponse(
        event.id(),
        event.eventId(),
        event.eventType(),
        event.content(),
        event.deliveryStatus(),
        event.createdAt(),
        event.deliveryDate());
  }
}
