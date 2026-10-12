package com.cobre.notificator.infrastructure.adapter.in.kafka;

import com.cobre.notificator.domain.exception.InvalidEventException;
import com.cobre.notificator.domain.exception.RetryableDeliveryException;
import com.cobre.notificator.domain.port.in.FailExhaustedEventUseCase;
import com.cobre.notificator.infrastructure.kafka.TopicAttempts;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

/**
 * Closes the delivery of a message that reached the DLT. A message that cannot be parsed identifies
 * no delivery, so it is only counted and logged.
 */
@Component
class DltMessageHandler {

  private static final Logger log = LoggerFactory.getLogger(DltMessageHandler.class);
  static final String DLT_MESSAGES = "notificator.dlt.messages";

  private final KafkaMessageParser parser;
  private final FailExhaustedEventUseCase exhausted;
  private final MeterRegistry meterRegistry;

  DltMessageHandler(
      KafkaMessageParser parser, FailExhaustedEventUseCase exhausted, MeterRegistry meterRegistry) {
    this.parser = parser;
    this.exhausted = exhausted;
    this.meterRegistry = meterRegistry;
    // Known tag values exist at 0 from startup, so increase() does not miss the first one.
    for (DltReason reason : DltReason.values()) {
      meterRegistry.counter(DLT_MESSAGES, "reason", reason.tag());
    }
  }

  void handle(ConsumerRecord<String, String> record) {
    DeliveryRef delivery;
    try {
      delivery = parser.deliveryRef(record.value());
    } catch (InvalidEventException e) {
      count(DltReason.INVALID);
      log.atError()
          .addKeyValue("partition", record.partition())
          .addKeyValue("offset", record.offset())
          .addKeyValue("error", e.getMessage())
          .log("Invalid message sent to DLT");
      return;
    }
    DltReason reason = isRetryableFailure(record) ? DltReason.EXHAUSTED : DltReason.UNEXPECTED;
    count(reason);
    int attempt = TopicAttempts.failedAttemptInDlt(record.headers());
    log.atError()
        .addKeyValue("reason", reason.tag())
        .addKeyValue("event_id", delivery.eventId())
        .addKeyValue("client_id", delivery.clientId())
        .addKeyValue("generation", delivery.generation())
        .addKeyValue("attempt", attempt)
        .log("Message in DLT");
    exhausted.failExhausted(
        delivery.eventId(), delivery.clientId(), delivery.generation(), attempt);
  }

  private static boolean isRetryableFailure(ConsumerRecord<String, String> record) {
    String retryable = RetryableDeliveryException.class.getName();
    return retryable.equals(header(record, KafkaHeaders.EXCEPTION_FQCN))
        || retryable.equals(header(record, KafkaHeaders.EXCEPTION_CAUSE_FQCN));
  }

  private static String header(ConsumerRecord<String, String> record, String name) {
    Header header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }

  private void count(DltReason reason) {
    meterRegistry.counter(DLT_MESSAGES, "reason", reason.tag()).increment();
  }
}
