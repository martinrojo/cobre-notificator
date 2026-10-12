package com.cobre.notificator.domain.model;

import com.cobre.notificator.domain.exception.InvalidEventException;
import java.time.Instant;

/** Message of transactions.events.v1; also the webhook body. */
public record TransactionEvent(
    String eventId, String clientId, String eventType, String content, Instant createdAt) {

  /** Matches the VARCHAR(64) columns: longer values are rejected, never truncated. */
  static final int MAX_ID_LENGTH = 64;

  public TransactionEvent {
    requireId("event_id", eventId);
    requireId("client_id", clientId);
    requireId("event_type", eventType);
    if (content == null) {
      throw InvalidEventException.of("content is required");
    }
    if (createdAt == null) {
      throw InvalidEventException.of("created_at is required");
    }
  }

  static void requireId(String field, String value) {
    if (value == null || value.isBlank()) {
      throw InvalidEventException.of(field + " is required");
    }
    if (value.length() > MAX_ID_LENGTH) {
      throw InvalidEventException.of(field + " exceeds " + MAX_ID_LENGTH + " characters");
    }
  }
}
