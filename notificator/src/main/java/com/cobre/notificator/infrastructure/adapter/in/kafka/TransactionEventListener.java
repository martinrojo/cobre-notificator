package com.cobre.notificator.infrastructure.adapter.in.kafka;

import com.cobre.notificator.domain.exception.RetryableDeliveryException;
import com.cobre.notificator.domain.port.in.DeliverEventUseCase;
import com.cobre.notificator.infrastructure.kafka.TopicAttempts;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.retrytopic.SameIntervalTopicReuseStrategy;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.stereotype.Component;

/**
 * Both main topics (transactions and replay requests) and the retry topics run the same method, so
 * there is a single consumer per retry topic; the attempt comes from the topic and the generation
 * from the value (see {@link KafkaMessageParser#deliveryRef}). Only {@link
 * RetryableDeliveryException} moves a message to the next retry topic; anything else that is not a
 * transient DB error (retried in place, see KafkaRetryConfig) goes straight to the DLT.
 */
@Component
public class TransactionEventListener {

  private final DeliverEventUseCase delivery;
  private final KafkaMessageParser parser;
  private final DltMessageHandler dlt;

  TransactionEventListener(
      DeliverEventUseCase delivery, KafkaMessageParser parser, DltMessageHandler dlt) {
    this.delivery = delivery;
    this.parser = parser;
    this.dlt = dlt;
  }

  @RetryableTopic(
      attempts = "${notificator.delivery.retry.attempts}",
      backOff =
          @BackOff(
              delayString = "${notificator.delivery.retry.delay}",
              multiplierString = "${notificator.delivery.retry.multiplier}"),
      topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
      // One topic per retry even if two delays are equal: the attempt is derived from the topic.
      sameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.MULTIPLE_TOPICS,
      autoCreateTopics = "false",
      include = RetryableDeliveryException.class,
      // A failing DLT handler must not loop: the recovery job closes the event instead.
      dltStrategy = DltStrategy.FAIL_ON_ERROR,
      // Retry and DLT topics have 3 partitions; the main topic's concurrency would leave most idle.
      concurrency = "3")
  // Explicit groupId: Spring suffixes it per retry/DLT endpoint, so each topic has its own group.
  @KafkaListener(
      topics = {TopicAttempts.MAIN, TopicAttempts.REPLAY},
      groupId = "${spring.kafka.consumer.group-id}")
  public void onMessage(ConsumerRecord<String, String> record) {
    switch (record.topic()) {
      case TopicAttempts.MAIN -> delivery.first(parser.transactionEvent(record.value()));
      case TopicAttempts.REPLAY -> delivery.replay(parser.replayRequest(record.value()));
      default -> {
        DeliveryRef ref = parser.deliveryRef(record.value());
        delivery.retry(
            ref.eventId(),
            ref.clientId(),
            ref.generation(),
            TopicAttempts.attemptFor(record.topic()));
      }
    }
  }

  // Spring requires the DLT handler on the same bean as the @RetryableTopic listener.
  @DltHandler
  public void onDlt(ConsumerRecord<String, String> record) {
    dlt.handle(record);
  }
}
