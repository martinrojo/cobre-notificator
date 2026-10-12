package com.cobre.manager.infrastructure.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cobre.manager.domain.exception.ReplayPublishException;
import com.cobre.manager.domain.model.ReplayRequested;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class KafkaReplayRequestPublisherTest {

  private static final String TOPIC = "notifications.replay-requested.v1";
  private static final ReplayRequested REQUEST =
      new ReplayRequested("EVT003", "CLIENT002", 0, Instant.parse("2026-10-10T12:00:00.123Z"));

  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

  private final KafkaReplayRequestPublisher publisher =
      new KafkaReplayRequestPublisher(
          kafkaTemplate,
          JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build(),
          new ReplayProperties(TOPIC, Duration.ofMillis(100)));

  @AfterEach
  void clearInterrupt() {
    Thread.interrupted();
  }

  @Test
  void sendsJsonKeyedByEventId() {
    when(kafkaTemplate.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

    publisher.publish(REQUEST);

    verify(kafkaTemplate)
        .send(
            TOPIC,
            "EVT003",
            "{\"event_id\":\"EVT003\",\"client_id\":\"CLIENT002\",\"generation\":0,"
                + "\"requested_at\":\"2026-10-10T12:00:00.123Z\"}");
  }

  @Test
  void failedFutureIsPublishException() {
    when(kafkaTemplate.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("not enough replicas")));

    assertThatThrownBy(() -> publisher.publish(REQUEST)).isInstanceOf(ReplayPublishException.class);
  }

  @Test
  void missingAckWithinTimeoutIsPublishException() {
    when(kafkaTemplate.send(anyString(), anyString(), anyString()))
        .thenReturn(new CompletableFuture<>());

    assertThatThrownBy(() -> publisher.publish(REQUEST)).isInstanceOf(ReplayPublishException.class);
  }

  @Test
  void sendBlockedOnMetadataIsPublishException() {
    when(kafkaTemplate.send(anyString(), anyString(), anyString()))
        .thenThrow(new KafkaException("Send failed"));

    assertThatThrownBy(() -> publisher.publish(REQUEST)).isInstanceOf(ReplayPublishException.class);
  }

  @Test
  void producerThatCannotBeCreatedIsPublishException() {
    when(kafkaTemplate.send(anyString(), anyString(), anyString()))
        .thenThrow(
            new org.apache.kafka.common.KafkaException("Failed to construct kafka producer"));

    assertThatThrownBy(() -> publisher.publish(REQUEST)).isInstanceOf(ReplayPublishException.class);
  }

  @Test
  void interruptionIsPublishExceptionAndKeepsFlag() {
    when(kafkaTemplate.send(anyString(), anyString(), anyString()))
        .thenReturn(new CompletableFuture<>());
    Thread.currentThread().interrupt();

    assertThatThrownBy(() -> publisher.publish(REQUEST)).isInstanceOf(ReplayPublishException.class);
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
  }
}
