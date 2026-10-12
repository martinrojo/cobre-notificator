package com.cobre.notificator.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DeliveryOutcomeTest {

  @ParameterizedTest
  @CsvSource({
    "200, DELIVERED",
    "204, DELIVERED",
    "302, PERMANENT",
    "400, PERMANENT",
    "404, PERMANENT",
    "408, RETRYABLE",
    "429, RETRYABLE",
    "500, RETRYABLE",
    "503, RETRYABLE"
  })
  void classifiesStatus(int status, DeliveryOutcome expected) {
    assertThat(DeliveryOutcome.of(new WebhookResult.Responded(status))).isEqualTo(expected);
  }

  @Test
  void transportErrorIsRetryable() {
    assertThat(DeliveryOutcome.of(new WebhookResult.TransportError("timeout")))
        .isEqualTo(DeliveryOutcome.RETRYABLE);
  }

  @Test
  void circuitOpenIsRetryable() {
    assertThat(DeliveryOutcome.of(new WebhookResult.CircuitOpen()))
        .isEqualTo(DeliveryOutcome.RETRYABLE);
  }
}
