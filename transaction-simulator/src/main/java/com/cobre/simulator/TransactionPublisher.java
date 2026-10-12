package com.cobre.simulator;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** One virtual thread per client, each publishing once per interval. Never logs content. */
@Component
public class TransactionPublisher implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(TransactionPublisher.class);

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final JsonMapper jsonMapper;
  private final SimulatorProperties properties;

  public TransactionPublisher(
      KafkaTemplate<String, String> kafkaTemplate,
      JsonMapper jsonMapper,
      SimulatorProperties properties) {
    this.kafkaTemplate = kafkaTemplate;
    this.jsonMapper = jsonMapper;
    this.properties = properties;
  }

  /** Blocks forever: closing the executor waits for the client loops, keeping the app alive. */
  @Override
  public void run(ApplicationArguments args) {
    log.info(
        "Simulator started topic={} events_per_minute={} clients={} interval_ms={}",
        properties.topic(),
        properties.eventsPerMinute(),
        properties.clients(),
        properties.interval().toMillis());
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      properties.clientIds().forEach(clientId -> executor.submit(() -> runClient(clientId)));
    }
  }

  void runClient(String clientId) {
    long interval = properties.interval().toNanos();
    long next = System.nanoTime();
    while (!Thread.currentThread().isInterrupted()) {
      publishOne(clientId);
      // Deadline-based pacing: send time does not accumulate drift.
      next += interval;
      long wait = next - System.nanoTime();
      if (wait > 0) {
        try {
          Thread.sleep(Duration.ofNanos(wait));
        } catch (InterruptedException e) {
          return;
        }
      } else if (-wait > interval) {
        // Fell behind (e.g. send() blocked while Kafka is down): skip the deficit, no burst.
        next = System.nanoTime();
      }
    }
  }

  void publishOne(String clientId) {
    SimulatedEvent event = SimulatedEvent.random(clientId, ThreadLocalRandom.current());
    try {
      kafkaTemplate
          .send(properties.topic(), event.eventId(), jsonMapper.writeValueAsString(event))
          .whenComplete(
              (result, error) -> {
                if (error != null) {
                  logFailure(event, error);
                } else {
                  log.debug(
                      "Event published event_id={} client_id={} partition={}",
                      event.eventId(),
                      clientId,
                      result.getRecordMetadata().partition());
                }
              });
    } catch (RuntimeException e) {
      // send() throws after max.block.ms when there is no metadata.
      logFailure(event, e);
    }
  }

  private static void logFailure(SimulatedEvent event, Throwable error) {
    log.error(
        "Event publish failed event_id={} client_id={} error={}",
        event.eventId(),
        event.clientId(),
        error.toString());
  }
}
