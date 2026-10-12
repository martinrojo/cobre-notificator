package com.cobre.notificator.domain.model;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.notificator.domain.exception.InvalidEventException;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TransactionEventTest {

  private static final Instant NOW = Instant.parse("2026-10-11T10:00:00Z");
  private static final String LONG = "x".repeat(65);

  static Stream<Arguments> invalid() {
    return Stream.of(
        Arguments.of(null, "C1", "t", "c", NOW),
        Arguments.of(" ", "C1", "t", "c", NOW),
        Arguments.of(LONG, "C1", "t", "c", NOW),
        Arguments.of("E1", null, "t", "c", NOW),
        Arguments.of("E1", "", "t", "c", NOW),
        Arguments.of("E1", LONG, "t", "c", NOW),
        Arguments.of("E1", "C1", null, "c", NOW),
        Arguments.of("E1", "C1", LONG, "c", NOW),
        Arguments.of("E1", "C1", "t", null, NOW),
        Arguments.of("E1", "C1", "t", "c", null));
  }

  @ParameterizedTest
  @MethodSource("invalid")
  void rejectsInvalidFields(
      String eventId, String clientId, String eventType, String content, Instant createdAt) {
    assertThatThrownBy(() -> new TransactionEvent(eventId, clientId, eventType, content, createdAt))
        .isInstanceOf(InvalidEventException.class);
  }

  @Test
  void acceptsValidEventAtMaxLengthAndEmptyContent() {
    String max = "x".repeat(64);
    assertThatCode(() -> new TransactionEvent(max, max, max, "", NOW)).doesNotThrowAnyException();
  }
}
