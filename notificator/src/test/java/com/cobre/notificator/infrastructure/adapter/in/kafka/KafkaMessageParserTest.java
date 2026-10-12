package com.cobre.notificator.infrastructure.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.notificator.domain.exception.InvalidEventException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class KafkaMessageParserTest {

  private static final String TRANSACTION =
      """
      {"event_id":"EVT003","client_id":"CLIENT002","event_type":"credit_transfer",
       "content":"c","created_at":"2024-03-15T11:20:18Z"}""";
  private static final String REPLAY =
      """
      {"event_id":"EVT003","client_id":"CLIENT002","generation":1,
       "requested_at":"2026-10-10T12:00:00.123Z"}""";

  private final KafkaMessageParser parser =
      new KafkaMessageParser(
          JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build());

  @Test
  void transactionEventIsGenerationZero() {
    assertThat(parser.deliveryRef(TRANSACTION))
        .isEqualTo(new DeliveryRef("EVT003", "CLIENT002", 0));
  }

  @Test
  void replayRequestIsTheGenerationItOpened() {
    assertThat(parser.deliveryRef(REPLAY)).isEqualTo(new DeliveryRef("EVT003", "CLIENT002", 2));
  }

  @Test
  void emptyMessageIsInvalid() {
    assertThatThrownBy(() -> parser.deliveryRef(null))
        .isInstanceOf(InvalidEventException.class)
        .hasMessageContaining("empty message");
  }

  @Test
  void malformedJsonIsInvalidWithoutQuotingThePayload() {
    assertThatThrownBy(() -> parser.deliveryRef("secret-payload"))
        .isInstanceOf(InvalidEventException.class)
        .hasMessageContaining("malformed JSON")
        .hasMessageNotContaining("secret-payload");
  }
}
