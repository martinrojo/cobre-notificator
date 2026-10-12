package com.cobre.notificator.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class CircuitStateLoggingTest {

  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger(CircuitStateLogging.class);
  private final CircuitBreakerRegistry circuitBreakers = CircuitBreakerRegistry.ofDefaults();

  @BeforeEach
  void setUp() {
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(logs);
  }

  @Test
  void logsEachTransitionOfABreakerCreatedLater() {
    new CircuitStateLogging(circuitBreakers);

    circuitBreakers.circuitBreaker("CLIENT002").transitionToOpenState();

    assertThat(logs.list).hasSize(1);
    ILoggingEvent event = logs.list.getFirst();
    assertThat(event.getLevel()).isEqualTo(Level.INFO);
    assertThat(event.getFormattedMessage()).isEqualTo("Circuit state changed");
    assertThat(keyValues(event)).containsExactly("client_id=CLIENT002", "from=CLOSED", "to=OPEN");
  }

  @Test
  void logsBreakersThatAlreadyExisted() {
    circuitBreakers.circuitBreaker("CLIENT001");
    new CircuitStateLogging(circuitBreakers);

    circuitBreakers.circuitBreaker("CLIENT001").transitionToOpenState();

    assertThat(logs.list).hasSize(1);
  }

  private static List<String> keyValues(ILoggingEvent event) {
    return event.getKeyValuePairs().stream().map(kv -> kv.key + "=" + kv.value).toList();
  }
}
