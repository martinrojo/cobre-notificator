package com.cobre.notificator.infrastructure.adapter.out.messaging;

import com.cobre.notificator.domain.model.NotificationEvent;
import com.cobre.notificator.domain.model.ReplayRequest;
import com.cobre.notificator.domain.port.out.RetryPublisher;
import com.cobre.notificator.infrastructure.kafka.TopicAttempts;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Recovery side: rebuilds the message from the row, with the key and value format of the flow that
 * opened its generation (transactions.events.v1 for 0, the replay request that opened it
 * otherwise), and Spring's attempts header so a later DLT knows the failed attempt.
 */
@Component
public class KafkaRetryPublisher implements RetryPublisher {

  private static final long PUBLISH_TIMEOUT_MS = 10_000;

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final JsonMapper jsonMapper;

  public KafkaRetryPublisher(KafkaTemplate<String, String> kafkaTemplate, JsonMapper jsonMapper) {
    this.kafkaTemplate = kafkaTemplate;
    this.jsonMapper = jsonMapper;
  }

  @Override
  public void publish(NotificationEvent event, int lastAttempt) {
    String topic = TopicAttempts.retryTopicAfter(lastAttempt);
    ProducerRecord<String, String> record =
        event.generation() == 0
            ? new ProducerRecord<>(
                topic, event.eventId(), jsonMapper.writeValueAsString(event.toTransactionEvent()))
            : new ProducerRecord<>(
                topic,
                event.eventId(),
                jsonMapper.writeValueAsString(
                    new ReplayRequest(
                        event.eventId(),
                        event.clientId(),
                        event.generation() - 1,
                        event.lastReplayAt())));
    record.headers().add(TopicAttempts.ATTEMPTS_HEADER, TopicAttempts.attemptsHeader(lastAttempt));
    try {
      kafkaTemplate.send(record).get(PUBLISH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Retry publish interrupted", e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("Retry publish failed", e);
    }
  }
}
