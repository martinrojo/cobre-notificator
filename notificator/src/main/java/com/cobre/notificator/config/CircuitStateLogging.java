package com.cobre.notificator.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * One INFO line per state change of a client's circuit, instead of one per rejected call. Breakers
 * are created on first use per client, so each one is subscribed as it is added.
 */
@Configuration
public class CircuitStateLogging {

  private static final Logger log = LoggerFactory.getLogger(CircuitStateLogging.class);

  public CircuitStateLogging(CircuitBreakerRegistry circuitBreakers) {
    circuitBreakers.getAllCircuitBreakers().forEach(CircuitStateLogging::subscribe);
    circuitBreakers.getEventPublisher().onEntryAdded(e -> subscribe(e.getAddedEntry()));
  }

  private static void subscribe(CircuitBreaker breaker) {
    breaker
        .getEventPublisher()
        .onStateTransition(
            e ->
                log.atInfo()
                    .addKeyValue("client_id", e.getCircuitBreakerName())
                    .addKeyValue("from", e.getStateTransition().getFromState())
                    .addKeyValue("to", e.getStateTransition().getToState())
                    .log("Circuit state changed"));
  }
}
