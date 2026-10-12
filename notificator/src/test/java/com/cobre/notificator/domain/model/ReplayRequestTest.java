package com.cobre.notificator.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.notificator.domain.exception.InvalidEventException;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ReplayRequestTest {

  private static final Instant NOW = Instant.parse("2026-10-10T12:00:00.123Z");
  private static final String LONG = "x".repeat(65);

  static Stream<Arguments> invalid() {
    return Stream.of(
        Arguments.of(null, "C1", 0, NOW),
        Arguments.of(" ", "C1", 0, NOW),
        Arguments.of(LONG, "C1", 0, NOW),
        Arguments.of("E1", null, 0, NOW),
        Arguments.of("E1", LONG, 0, NOW),
        Arguments.of("E1", "C1", null, NOW),
        Arguments.of("E1", "C1", -1, NOW),
        Arguments.of("E1", "C1", 0, null));
  }

  @ParameterizedTest
  @MethodSource("invalid")
  void rejectsInvalidFields(
      String eventId, String clientId, Integer generation, Instant requestedAt) {
    assertThatThrownBy(() -> new ReplayRequest(eventId, clientId, generation, requestedAt))
        .isInstanceOf(InvalidEventException.class);
  }

  @Test
  void deliversTheNextGeneration() {
    assertThat(new ReplayRequest("E1", "C1", 2, NOW).deliveryGeneration()).isEqualTo(3);
  }
}
