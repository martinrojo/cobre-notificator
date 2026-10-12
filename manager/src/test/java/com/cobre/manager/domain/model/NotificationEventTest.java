package com.cobre.manager.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NotificationEventTest {

  @ParameterizedTest
  @CsvSource({
    "FAILED, false, PUBLISH",
    "FAILED, true, PUBLISH",
    "PENDING, true, ALREADY_IN_PROGRESS",
    "RETRYING, true, ALREADY_IN_PROGRESS",
    "PENDING, false, NOT_ALLOWED",
    "RETRYING, false, NOT_ALLOWED",
    "DELIVERED, false, NOT_ALLOWED",
    "DELIVERED, true, NOT_ALLOWED",
    "NOT_SUBSCRIBED, false, NOT_ALLOWED",
    "NOT_SUBSCRIBED, true, NOT_ALLOWED"
  })
  void replayDecisionDependsOnStatusAndLastReplay(
      DeliveryStatus status, boolean replayed, ReplayDecision expected) {
    var event =
        new NotificationEvent(
            "id-EVT001",
            "EVT001",
            "CLIENT001",
            "credit_card_payment",
            "content",
            status,
            Instant.parse("2024-03-15T09:30:22Z"),
            null,
            replayed ? Instant.parse("2026-10-10T12:00:00Z") : null,
            0);

    assertThat(event.replayDecision()).isEqualTo(expected);
  }
}
