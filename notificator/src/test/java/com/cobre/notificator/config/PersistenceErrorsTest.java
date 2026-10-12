package com.cobre.notificator.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.notificator.domain.exception.RetryableDeliveryException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

class PersistenceErrorsTest {

  private static final ConsumerRecord<String, String> RECORD =
      new ConsumerRecord<>("transactions.events.v1", 0, 0, "CLIENT001", "{}");

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final KafkaRetryConfig.PersistenceErrors listener =
      new KafkaRetryConfig.PersistenceErrors(meterRegistry);

  @Test
  void transientDatabaseFailureIsCountedByItsType() {
    listener.failedDelivery(
        RECORD,
        new ListenerExecutionFailedException("Listener failed", new QueryTimeoutException("slow")),
        1);

    assertThat(
            meterRegistry
                .get(KafkaRetryConfig.PERSISTENCE_ERRORS)
                .tag("exception", "QueryTimeoutException")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void webhookFailureIsNotCounted() {
    listener.failedDelivery(
        RECORD,
        new ListenerExecutionFailedException(
            "Listener failed", RetryableDeliveryException.of("EVT100", 1)),
        1);

    assertThat(meterRegistry.find(KafkaRetryConfig.PERSISTENCE_ERRORS).counter()).isNull();
  }

  @Test
  void nonTransientDatabaseFailureIsNotCounted() {
    // Not retried in place: it goes to the DLT instead of blocking the partition.
    listener.failedDelivery(
        RECORD,
        new ListenerExecutionFailedException(
            "Listener failed", new DataIntegrityViolationException("duplicate")),
        1);

    assertThat(meterRegistry.find(KafkaRetryConfig.PERSISTENCE_ERRORS).counter()).isNull();
  }
}
