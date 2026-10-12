package com.cobre.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class SimulatedEventTest {

  private final Random random = new Random(42);

  @Test
  void generatesValidUniqueEvents() {
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < 10_000; i++) {
      SimulatedEvent event = SimulatedEvent.random("CLIENT001", random);
      assertThat(event.eventId()).hasSizeLessThanOrEqualTo(64);
      assertThat(ids.add(event.eventId())).isTrue();
      assertThat(event.clientId()).isEqualTo("CLIENT001");
      assertThat(SimulatedEvent.EVENT_TYPES).contains(event.eventType());
      assertThat(event.content()).isNotBlank();
      assertThat(event.createdAt()).isNotNull();
    }
  }

  @Test
  void contentDescribesTypeAndAmount() {
    SimulatedEvent event = SimulatedEvent.random("CLIENT001", random);
    assertThat(event.content()).matches("[A-Z][a-z ]+ of \\$\\d+\\.\\d{2}");
  }

  @Test
  void serializesInSnakeCaseWithIsoInstant() {
    JsonMapper mapper =
        JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    SimulatedEvent event =
        new SimulatedEvent(
            "EVT-1", "CLIENT003", "credit_deposit", "x", Instant.parse("2026-10-11T10:00:00Z"));

    JsonNode json = mapper.readTree(mapper.writeValueAsString(event));

    assertThat(json.propertyNames())
        .containsExactlyInAnyOrder("event_id", "client_id", "event_type", "content", "created_at");
    assertThat(json.get("created_at").asString()).isEqualTo("2026-10-11T10:00:00Z");
  }
}
