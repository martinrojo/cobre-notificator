package com.cobre.notificator.infrastructure.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cobre.notificator.domain.model.DeliveryStatus;
import com.cobre.notificator.domain.model.NotificationEvent;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class KafkaRetryPublisherTest {

  private static final Instant CREATED = Instant.parse("2024-03-15T11:20:18Z");
  private static final Instant REPLAYED = Instant.parse("2026-10-10T12:00:00.123Z");

  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

  private final KafkaRetryPublisher publisher =
      new KafkaRetryPublisher(
          kafkaTemplate,
          JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build());

  @BeforeEach
  @SuppressWarnings("unchecked")
  void ack() {
    when(kafkaTemplate.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
  }

  @Test
  void generationZeroKeepsTheTransactionFormat() {
    ProducerRecord<String, String> sent = publish(row(0, null), 1);

    assertThat(sent.topic()).isEqualTo("notificator.retry.v1-0");
    assertThat(sent.key()).isEqualTo("EVT003");
    assertThat(sent.value()).contains("\"content\":\"c\"").doesNotContain("generation");
  }

  @Test
  void replayGenerationIsRepublishedAsTheRequestThatOpenedIt() {
    ProducerRecord<String, String> sent = publish(row(2, REPLAYED), 2);

    assertThat(sent.topic()).isEqualTo("notificator.retry.v1-1");
    assertThat(sent.key()).isEqualTo("EVT003");
    assertThat(sent.value())
        .isEqualTo(
            "{\"event_id\":\"EVT003\",\"client_id\":\"CLIENT002\",\"generation\":1,"
                + "\"requested_at\":\"2026-10-10T12:00:00.123Z\"}");
  }

  @SuppressWarnings("unchecked")
  private ProducerRecord<String, String> publish(NotificationEvent event, int lastAttempt) {
    publisher.publish(event, lastAttempt);
    ArgumentCaptor<ProducerRecord<String, String>> sent =
        ArgumentCaptor.forClass(ProducerRecord.class);
    verify(kafkaTemplate).send(sent.capture());
    return sent.getValue();
  }

  private static NotificationEvent row(int generation, Instant lastReplayAt) {
    return new NotificationEvent(
        "01a1311c-4907-7a3c-9814-0adb01ff9e75",
        "EVT003",
        "CLIENT002",
        "credit_transfer",
        "c",
        DeliveryStatus.RETRYING,
        1,
        CREATED,
        generation,
        lastReplayAt);
  }
}
