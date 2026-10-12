package com.cobre.notificator.infrastructure.adapter.in.kafka;

import com.cobre.notificator.domain.exception.InvalidEventException;
import com.cobre.notificator.domain.model.ReplayRequest;
import com.cobre.notificator.domain.model.TransactionEvent;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Turns message values into domain objects; any bad value is an {@link InvalidEventException}. */
@Component
class KafkaMessageParser {

  private final JsonMapper jsonMapper;

  KafkaMessageParser(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

  TransactionEvent transactionEvent(String value) {
    return parse(value, TransactionEvent.class);
  }

  ReplayRequest replayRequest(String value) {
    return parse(value, ReplayRequest.class);
  }

  /**
   * Retry and DLT messages keep the value of their origin: a replay request (it has {@code
   * generation}) belongs to the generation it opened, a transaction event to generation 0.
   */
  DeliveryRef deliveryRef(String value) {
    if (isReplayRequest(value)) {
      ReplayRequest request = replayRequest(value);
      return new DeliveryRef(request.eventId(), request.clientId(), request.deliveryGeneration());
    }
    TransactionEvent event = transactionEvent(value);
    return new DeliveryRef(event.eventId(), event.clientId(), 0);
  }

  private boolean isReplayRequest(String value) {
    try {
      return value != null && jsonMapper.readTree(value).has("generation");
    } catch (JacksonException e) {
      return false; // parse() rejects it with the proper reason
    }
  }

  private <T> T parse(String value, Class<T> type) {
    if (value == null) {
      throw InvalidEventException.of("empty message");
    }
    try {
      return jsonMapper.readValue(value, type);
    } catch (JacksonException e) {
      if (e.getCause() instanceof InvalidEventException invalid) {
        throw invalid;
      }
      // Only the exception type: Jackson messages may quote the payload.
      throw InvalidEventException.of("malformed JSON (" + e.getClass().getSimpleName() + ")");
    }
  }
}
