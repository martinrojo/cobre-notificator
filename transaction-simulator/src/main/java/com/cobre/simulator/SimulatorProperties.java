package com.cobre.simulator;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Total rate is split evenly: each client publishes once per {@link #interval()}. */
@ConfigurationProperties("simulator")
public record SimulatorProperties(String topic, int eventsPerMinute, int clients) {

  public SimulatorProperties {
    if (eventsPerMinute <= 0) {
      throw new IllegalArgumentException("simulator.events-per-minute must be > 0");
    }
    if (clients <= 0) {
      throw new IllegalArgumentException("simulator.clients must be > 0");
    }
  }

  public Duration interval() {
    return Duration.ofMinutes(clients).dividedBy(eventsPerMinute);
  }

  public List<String> clientIds() {
    return IntStream.rangeClosed(1, clients).mapToObj("CLIENT%03d"::formatted).toList();
  }
}
