package com.cobre.notificator.domain.model;

import java.time.Instant;

/** A notification_event row: the single source of truth for every delivery attempt. */
public record NotificationEvent(
    String id,
    String eventId,
    String clientId,
    String eventType,
    String content,
    DeliveryStatus deliveryStatus,
    int attempts,
    Instant createdAt,
    int generation,
    Instant lastReplayAt) {

  public TransactionEvent toTransactionEvent() {
    return new TransactionEvent(eventId, clientId, eventType, content, createdAt);
  }
}
