package com.cobre.manager.infrastructure.adapter.out.messaging;

import com.cobre.manager.domain.exception.ReplayPublishException;
import com.cobre.manager.domain.model.ReplayRequested;
import com.cobre.manager.domain.port.out.ReplayRequestPublisher;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Synchronous publish: returns only after the broker ack. Serialized to a plain JSON string (no
 * type headers) so the consumer does not depend on manager classes.
 */
@Component
public class KafkaReplayRequestPublisher implements ReplayRequestPublisher {

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final JsonMapper jsonMapper;
  private final ReplayProperties properties;

  public KafkaReplayRequestPublisher(
      KafkaTemplate<String, String> kafkaTemplate,
      JsonMapper jsonMapper,
      ReplayProperties properties) {
    this.kafkaTemplate = kafkaTemplate;
    this.jsonMapper = jsonMapper;
    this.properties = properties;
  }

  @Override
  public void publish(ReplayRequested request) {
    String payload = jsonMapper.writeValueAsString(request);
    try {
      kafkaTemplate
          .send(properties.topic(), request.clientId(), payload)
          .get(properties.publishTimeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ReplayPublishException.of(request.eventId(), e);
    } catch (ExecutionException | TimeoutException | KafkaException e) {
      // KafkaException: send() gave up after max.block.ms waiting for metadata.
      throw ReplayPublishException.of(request.eventId(), e);
    }
  }
}
