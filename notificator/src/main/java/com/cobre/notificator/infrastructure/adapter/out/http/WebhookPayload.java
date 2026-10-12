package com.cobre.notificator.infrastructure.adapter.out.http;

import com.cobre.notificator.domain.model.TransactionEvent;
import java.time.Instant;

/** Webhook body: the event plus the id of its notification. */
record WebhookPayload(
    String notificationEventId,
    String eventId,
    String clientId,
    String eventType,
    String content,
    Instant createdAt) {

  static WebhookPayload of(String notificationEventId, TransactionEvent event) {
    return new WebhookPayload(
        notificationEventId,
        event.eventId(),
        event.clientId(),
        event.eventType(),
        event.content(),
        event.createdAt());
  }
}
