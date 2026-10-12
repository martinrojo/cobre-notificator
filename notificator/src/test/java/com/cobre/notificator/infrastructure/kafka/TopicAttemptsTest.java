package com.cobre.notificator.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class TopicAttemptsTest {

  @ParameterizedTest
  @CsvSource({
    "transactions.events.v1, 1",
    "notifications.replay-requested.v1, 1",
    "notificator.retry.v1-0, 2",
    "notificator.retry.v1-1, 3",
    "notificator.retry.v1-2, 4"
  })
  void attemptForTopic(String topic, int attempt) {
    assertThat(TopicAttempts.attemptFor(topic)).isEqualTo(attempt);
  }

  @ParameterizedTest
  @CsvSource({
    "1, notificator.retry.v1-0",
    "2, notificator.retry.v1-1",
    "3, notificator.retry.v1-2"
  })
  void retryTopicIsTheInverse(int lastAttempt, String topic) {
    assertThat(TopicAttempts.retryTopicAfter(lastAttempt)).isEqualTo(topic);
    assertThat(TopicAttempts.attemptFor(topic)).isEqualTo(lastAttempt + 1);
  }

  @ParameterizedTest
  @CsvSource({"1, 1", "3, 3", "4, 4"})
  void dltAttemptRoundTripsThroughHeader(int lastAttempt, int expected) {
    assertThat(TopicAttempts.failedAttemptInDlt(TopicAttempts.attemptsHeader(lastAttempt)))
        .isEqualTo(expected);
  }

  @Test
  void dltAttemptFromSingleByteHeader() {
    assertThat(TopicAttempts.failedAttemptInDlt(new byte[] {5})).isEqualTo(4);
  }

  @ParameterizedTest
  @ValueSource(strings = {"notificator.dlt.v1", "transactions.events.v2"})
  void rejectsOtherTopics(String topic) {
    assertThatThrownBy(() -> TopicAttempts.attemptFor(topic))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void dltWithoutAttemptsHeaderFailedAtAttemptOne() {
    assertThat(TopicAttempts.failedAttemptInDlt(new RecordHeaders())).isEqualTo(1);
  }

  @Test
  void dltAttemptComesFromTheAttemptsHeader() {
    RecordHeaders headers = new RecordHeaders();
    headers.add(TopicAttempts.ATTEMPTS_HEADER, TopicAttempts.attemptsHeader(3));

    assertThat(TopicAttempts.failedAttemptInDlt(headers)).isEqualTo(3);
  }
}
