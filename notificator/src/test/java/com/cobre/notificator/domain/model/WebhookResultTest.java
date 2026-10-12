package com.cobre.notificator.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WebhookResultTest {

  @ParameterizedTest
  @CsvSource({
    "200, false",
    "302, false",
    "400, false",
    "404, false",
    "408, false",
    "429, true",
    "500, false",
    "501, false",
    "502, true",
    "503, true",
    "504, true",
    "505, false"
  })
  void statusIsUnavailableOnlyWhenTheClientAsksToStop(int status, boolean expected) {
    assertThat(new WebhookResult.Responded(status).isUnavailable()).isEqualTo(expected);
  }

  @Test
  void transportErrorIsUnavailable() {
    assertThat(new WebhookResult.TransportError("HttpTimeoutException").isUnavailable()).isTrue();
  }

  @Test
  void circuitOpenIsNotCounted() {
    assertThat(new WebhookResult.CircuitOpen().isUnavailable()).isFalse();
  }
}
