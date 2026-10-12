package com.cobre.notificator.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RetryScheduleTest {

  private final RetrySchedule schedule = new RetrySchedule(4, Duration.ofSeconds(60), 5);

  @ParameterizedTest
  @CsvSource({"1, 60", "2, 300", "3, 1500", "4, 0"})
  void delayAfterAttempt(int attempt, long seconds) {
    assertThat(schedule.delayAfter(attempt)).isEqualTo(Duration.ofSeconds(seconds));
  }
}
