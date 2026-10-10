package com.cobre.manager.domain.model;

import java.time.Instant;

public record NotificationEvent(
    String eventId,
    String clientId,
    String eventType,
    String content,
    DeliveryStatus deliveryStatus,
    Instant createdAt,
    Instant deliveryDate,
    Instant lastReplayAt) {

  /** A replay already taken by the notificator is in progress; asking again has no effect. */
  public ReplayDecision replayDecision() {
    return switch (deliveryStatus) {
      case FAILED -> ReplayDecision.PUBLISH;
      case PENDING, RETRYING ->
          lastReplayAt != null ? ReplayDecision.ALREADY_IN_PROGRESS : ReplayDecision.NOT_ALLOWED;
      case DELIVERED, NOT_SUBSCRIBED -> ReplayDecision.NOT_ALLOWED;
    };
  }
}
