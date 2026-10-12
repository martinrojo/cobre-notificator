package com.cobre.notificator.infrastructure.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.cobre.notificator.domain.exception.InvalidEventException;
import com.cobre.notificator.domain.model.ReplayRequest;
import com.cobre.notificator.domain.port.in.DeliverEventUseCase;
import com.cobre.notificator.domain.port.in.FailExhaustedEventUseCase;
import com.cobre.notificator.infrastructure.kafka.TopicAttempts;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class TransactionEventListenerTest {

  private static final String TRANSACTION =
      """
      {"event_id":"EVT003","client_id":"CLIENT002","event_type":"credit_transfer",
       "content":"c","created_at":"2024-03-15T11:20:18Z"}""";
  private static final String REPLAY =
      """
      {"event_id":"EVT003","client_id":"CLIENT002","generation":1,
       "requested_at":"2026-10-10T12:00:00.123Z"}""";

  private final DeliverEventUseCase delivery = mock(DeliverEventUseCase.class);
  private final FailExhaustedEventUseCase exhausted = mock(FailExhaustedEventUseCase.class);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final KafkaMessageParser parser =
      new KafkaMessageParser(
          JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build());
  private final TransactionEventListener listener =
      new TransactionEventListener(
          delivery, parser, new DltMessageHandler(parser, exhausted, meterRegistry));

  @Test
  void dltCountersExistAtZeroFromStartup() {
    for (String reason : List.of("exhausted", "invalid", "unexpected")) {
      assertThat(meterRegistry.find(DltMessageHandler.DLT_MESSAGES).tag("reason", reason).counter())
          .isNotNull()
          .extracting(c -> c.count())
          .isEqualTo(0.0);
    }
  }

  @Test
  void replayTopicStartsAReplay() {
    listener.onMessage(record(TopicAttempts.REPLAY, REPLAY));

    verify(delivery)
        .replay(
            new ReplayRequest("EVT003", "CLIENT002", 1, Instant.parse("2026-10-10T12:00:00.123Z")));
  }

  @Test
  void retryOfAReplayBelongsToTheGenerationItOpened() {
    listener.onMessage(record("notificator.retry.v1-0", REPLAY));

    verify(delivery).retry("EVT003", "CLIENT002", 2, 2);
  }

  @Test
  void retryOfATransactionEventIsGenerationZero() {
    listener.onMessage(record("notificator.retry.v1-1", TRANSACTION));

    verify(delivery).retry("EVT003", "CLIENT002", 0, 3);
  }

  @Test
  void replayWithoutGenerationIsInvalid() {
    String noGeneration =
        """
        {"event_id":"EVT003","client_id":"CLIENT002","requested_at":"2026-10-10T12:00:00Z"}""";

    assertThatThrownBy(() -> listener.onMessage(record(TopicAttempts.REPLAY, noGeneration)))
        .isInstanceOf(InvalidEventException.class);
    verify(delivery, never()).replay(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void dltOfAReplayFailsItsGeneration() {
    // Without the attempts header the message failed at attempt 1.
    listener.onDlt(record(TopicAttempts.DLT, REPLAY));

    verify(exhausted).failExhausted("EVT003", "CLIENT002", 2, 1);
  }

  @Test
  void invalidDltMessageTouchesNothing() {
    listener.onDlt(record(TopicAttempts.DLT, "not json"));

    verify(exhausted, never()).failExhausted(anyString(), anyString(), anyInt(), anyInt());
  }

  private static ConsumerRecord<String, String> record(String topic, String value) {
    return new ConsumerRecord<>(topic, 0, 0, "EVT003", value);
  }
}
