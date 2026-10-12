package com.cobre.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class TransactionPublisherTest {

  private static final String TOPIC = "transactions.events.v1";

  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

  private final JsonMapper jsonMapper =
      JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
  private final TransactionPublisher publisher =
      new TransactionPublisher(kafkaTemplate, jsonMapper, new SimulatorProperties(TOPIC, 3000, 8));

  @Test
  void keyIsTheEventIdOfTheValue() {
    when(kafkaTemplate.send(anyString(), anyString(), anyString()))
        .thenReturn(new CompletableFuture<>());

    publisher.publishOne("CLIENT005");

    ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
    verify(kafkaTemplate).send(eq(TOPIC), key.capture(), value.capture());
    JsonNode event = jsonMapper.readTree(value.getValue());
    assertThat(key.getValue()).isEqualTo(event.get("event_id").asString());
    assertThat(event.get("client_id").asString()).isEqualTo("CLIENT005");
  }

  @Test
  void failedSendIsLoggedWithoutContent(CapturedOutput output) {
    when(kafkaTemplate.send(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.failedFuture(new KafkaException("broker down")));

    assertThatNoException().isThrownBy(() -> publisher.publishOne("CLIENT001"));

    assertThat(output).contains("Event publish failed").contains("client_id=CLIENT001");
    assertThat(output).doesNotContain(" of $");
  }

  @Test
  void sendThrowingDoesNotStopTheLoop(CapturedOutput output) throws Exception {
    when(kafkaTemplate.send(anyString(), anyString(), anyString()))
        .thenThrow(new KafkaException("no metadata"));

    Thread loop = Thread.ofVirtual().start(() -> publisher.runClient("CLIENT002"));
    verify(kafkaTemplate, timeout(2000).atLeast(3)).send(eq(TOPIC), anyString(), any());
    loop.interrupt();
    loop.join(2000);

    assertThat(loop.isAlive()).isFalse();
    assertThat(output).contains("Event publish failed").doesNotContain(" of $");
  }

  @Test
  void loopKeepsTheConfiguredPace() throws Exception {
    when(kafkaTemplate.send(anyString(), anyString(), anyString()))
        .thenReturn(new CompletableFuture<SendResult<String, String>>());

    Thread loop = Thread.ofVirtual().start(() -> publisher.runClient("CLIENT003"));
    Thread.sleep(1000);
    loop.interrupt();
    loop.join(2000);

    // 160 ms interval: 1 immediate send + 6 in the first second (7), allow scheduling jitter.
    ArgumentCaptor<String> values = ArgumentCaptor.forClass(String.class);
    verify(kafkaTemplate, atLeast(6)).send(eq(TOPIC), anyString(), values.capture());
    assertThat(values.getAllValues()).hasSizeBetween(6, 8);
  }
}
