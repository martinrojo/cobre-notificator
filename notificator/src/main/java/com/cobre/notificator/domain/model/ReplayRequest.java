package com.cobre.notificator.domain.model;

import com.cobre.notificator.domain.exception.InvalidEventException;
import java.time.Instant;

/**
 * Message of notifications.replay-requested.v1. {@code generation} is the one the manager read: the
 * replay opens {@code generation + 1}, and so do its retries, which keep this value.
 */
public record ReplayRequest(
    String eventId, String clientId, Integer generation, Instant requestedAt) {

  public ReplayRequest {
    TransactionEvent.requireId("event_id", eventId);
    TransactionEvent.requireId("client_id", clientId);
    // Integer, not int: a missing field must be rejected, not read as generation 0.
    if (generation == null || generation < 0) {
      throw InvalidEventException.of("generation is required and must not be negative");
    }
    if (requestedAt == null) {
      throw InvalidEventException.of("requested_at is required");
    }
  }

  /** The generation this request, and every retry of it, delivers. */
  public int deliveryGeneration() {
    return generation + 1;
  }
}
