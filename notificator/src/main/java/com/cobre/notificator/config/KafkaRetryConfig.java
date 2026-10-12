package com.cobre.notificator.config;

import com.cobre.notificator.infrastructure.kafka.TopicAttempts;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.retrytopic.DestinationTopic;
import org.springframework.kafka.retrytopic.RetryTopicComponentFactory;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationSupport;
import org.springframework.kafka.retrytopic.RetryTopicNamesProviderFactory;
import org.springframework.kafka.retrytopic.SuffixingRetryTopicNamesProviderFactory.SuffixingRetryTopicNamesProvider;
import org.springframework.kafka.support.KafkaUtils;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaRetryConfig extends RetryTopicConfigurationSupport {

  static final String PERSISTENCE_ERRORS = "notificator.persistence.errors";

  /** Database failures retried in place; also what counts as a persistence error. */
  private static final List<Class<? extends Exception>> BLOCKING_RETRY =
      List.of(
          TransientDataAccessException.class,
          DataAccessResourceFailureException.class,
          RecoverableDataAccessException.class);

  private final MeterRegistry meterRegistry;

  public KafkaRetryConfig(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
    // The default formatter is ProducerRecord.toString(), which includes the value (the event
    // content) in spring-kafka's error logs. Log only where the record was going.
    KafkaUtils.setProducerRecordFormatter(
        pr -> pr.topic() + "-" + pr.partition() + " key=" + pr.key());
  }

  /**
   * Fixed topic names from kafka/create-topics.sh: Spring would derive them from the main topic
   * (transactions.events.v1-retry-0, ...). Listener ids and groups keep the default suffixes.
   */
  @Override
  protected RetryTopicComponentFactory createComponentFactory() {
    return new RetryTopicComponentFactory() {
      @Override
      public RetryTopicNamesProviderFactory retryTopicNamesProviderFactory() {
        return properties ->
            new SuffixingRetryTopicNamesProvider(properties) {
              @Override
              public String getTopicName(String topic) {
                return topicName(properties, topic);
              }
            };
      }
    };
  }

  static String topicName(DestinationTopic.Properties properties, String topic) {
    if (properties.isMainEndpoint()) {
      return topic;
    }
    if (properties.isDltTopic()) {
      return TopicAttempts.DLT;
    }
    // SUFFIX_WITH_INDEX_VALUE + MULTIPLE_TOPICS: "-retry-0", "-retry-1", ...
    String suffix = properties.suffix();
    String index = suffix.substring(suffix.lastIndexOf('-') + 1);
    if (!index.chars().allMatch(Character::isDigit) || index.isEmpty()) {
      throw new IllegalStateException("Retry topic suffix without index: " + suffix);
    }
    return TopicAttempts.RETRY_PREFIX + index;
  }

  /**
   * A database outage is retried in place, without moving the message to a retry topic: a message
   * only reaches a retry topic after its claim was written, and never reaches the DLT without its
   * row. Non-transient errors (e.g. integrity violations) are excluded: retrying them would block
   * the partition forever.
   */
  @Override
  protected void configureBlockingRetries(BlockingRetriesConfigurer blockingRetries) {
    ExponentialBackOff backOff = new ExponentialBackOff(Duration.ofSeconds(1).toMillis(), 2);
    backOff.setMaxInterval(Duration.ofSeconds(30).toMillis());
    @SuppressWarnings("unchecked")
    Class<? extends Exception>[] types = BLOCKING_RETRY.toArray(Class[]::new);
    blockingRetries.retryOn(types).backOff(backOff);
  }

  @Override
  protected void configureCustomizers(CustomizersConfigurer customizers) {
    customizers.customizeErrorHandler(
        handler -> handler.setRetryListeners(new PersistenceErrors(meterRegistry)));
  }

  /**
   * Counts each in-place retry of a database failure. The error handler also sees webhook failures
   * (RetryableDeliveryException, sent to the next retry topic): those are not counted.
   */
  static final class PersistenceErrors implements RetryListener {

    private final MeterRegistry meterRegistry;

    PersistenceErrors(MeterRegistry meterRegistry) {
      this.meterRegistry = meterRegistry;
    }

    @Override
    public void failedDelivery(ConsumerRecord<?, ?> record, Exception ex, int deliveryAttempt) {
      for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
        if (isBlockingRetry(cause)) {
          meterRegistry
              .counter(PERSISTENCE_ERRORS, "exception", cause.getClass().getSimpleName())
              .increment();
          return;
        }
      }
    }

    private static boolean isBlockingRetry(Throwable cause) {
      return BLOCKING_RETRY.stream().anyMatch(type -> type.isInstance(cause));
    }
  }
}
