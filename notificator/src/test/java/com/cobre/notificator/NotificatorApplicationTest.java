package com.cobre.notificator;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.notificator.config.DeliveryProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Context wiring only: listeners and the recovery job stay idle, so no broker or DB is needed. */
@SpringBootTest(
    properties = {
      "DB_NAME=test",
      "DB_USER=test",
      "DB_PASSWORD=test",
      "spring.kafka.listener.auto-startup=false",
      "RECOVERY_INTERVAL=1h",
      "resilience4j.circuitbreaker.instances.CLIENT008.base-config=bajo-volumen"
    })
class NotificatorApplicationTest {

  @Autowired DeliveryProperties properties;
  @Autowired MeterRegistry meterRegistry;
  @Autowired CircuitBreakerRegistry circuitBreakers;

  @Test
  void bindsDeliveryProperties() {
    assertThat(properties.retry().attempts()).isEqualTo(4);
    assertThat(properties.retry().delay()).isEqualTo(Duration.ofSeconds(60));
    assertThat(properties.lease()).isEqualTo(Duration.ofSeconds(120));
    assertThat(properties.webhook().allowHttp()).isFalse();
    assertThat(properties.recovery().batchSize()).isEqualTo(100);
  }

  @Test
  void webhookLatencyUsesOnlyTheConfiguredBuckets() {
    assertThat(bucketsInMillis("notificator.webhook.latency"))
        .containsExactly(5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000);
  }

  @Test
  void clientBreakersUseTheDefaultConfig() {
    CircuitBreakerConfig config =
        circuitBreakers.circuitBreaker("CLIENT001").getCircuitBreakerConfig();
    assertThat(config.getSlidingWindowType())
        .isEqualTo(CircuitBreakerConfig.SlidingWindowType.TIME_BASED);
    assertThat(config.getSlidingWindowSize()).isEqualTo(60);
    assertThat(config.getMinimumNumberOfCalls()).isEqualTo(10);
    assertThat(config.getFailureRateThreshold()).isEqualTo(50);
    assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(2);
    assertThat(config.getMaxWaitDurationInHalfOpenState()).isEqualTo(Duration.ofSeconds(10));
    assertThat(config.isAutomaticTransitionFromOpenToHalfOpenEnabled()).isFalse();
    // Below the first retry delay, so each wave of retries can probe the webhook.
    assertThat(config.getWaitIntervalFunctionInOpenState().apply(1))
        .isEqualTo(Duration.ofSeconds(30).toMillis())
        .isLessThan(properties.retry().delay().toMillis());
  }

  @Test
  void assignedClientUsesItsProfileAndInheritsTheRest() {
    CircuitBreakerConfig base =
        circuitBreakers.circuitBreaker("CLIENT001").getCircuitBreakerConfig();
    CircuitBreakerConfig config =
        circuitBreakers.circuitBreaker("CLIENT008").getCircuitBreakerConfig();

    assertThat(config.getSlidingWindowSize()).isEqualTo(300);
    assertThat(config.getMinimumNumberOfCalls()).isEqualTo(5);
    assertThat(config.getSlidingWindowType()).isEqualTo(base.getSlidingWindowType());
    assertThat(config.getFailureRateThreshold()).isEqualTo(base.getFailureRateThreshold());
    assertThat(config.getPermittedNumberOfCallsInHalfOpenState())
        .isEqualTo(base.getPermittedNumberOfCallsInHalfOpenState());
    assertThat(config.getMaxWaitDurationInHalfOpenState())
        .isEqualTo(base.getMaxWaitDurationInHalfOpenState());
    assertThat(config.getWaitIntervalFunctionInOpenState().apply(1))
        .isEqualTo(base.getWaitIntervalFunctionInOpenState().apply(1))
        .isLessThan(properties.retry().delay().toMillis());
    assertThat(base.getSlidingWindowSize()).isEqualTo(60);
  }

  @Test
  void defaultWindowNeedsTheMinimumOfCallsWithinIt() {
    MutableClock clock = new MutableClock();
    CircuitBreaker breaker = withClock(clock);

    fail(breaker, 9);
    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

    clock.advance(Duration.ofSeconds(61));
    fail(breaker, 9);
    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

    fail(breaker, 1);
    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
  }

  @Test
  void shortBlipOfAHighVolumeClientDoesNotOpen() {
    CircuitBreaker breaker = withClock(new MutableClock());

    for (int i = 0; i < 600; i++) {
      breaker.onSuccess(0, TimeUnit.MILLISECONDS);
    }
    fail(breaker, 20);

    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  /** The default config from application.yml, on a clock the test moves. */
  private CircuitBreaker withClock(Clock clock) {
    return CircuitBreaker.of(
        "window",
        CircuitBreakerConfig.from(circuitBreakers.getDefaultConfig()).clock(clock).build());
  }

  private static void fail(CircuitBreaker breaker, int calls) {
    for (int i = 0; i < calls; i++) {
      breaker.onError(0, TimeUnit.MILLISECONDS, new IllegalStateException("unavailable"));
    }
  }

  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-10-12T00:00:00Z");

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }

  @Test
  void clientBreakerStateIsExportedByClient() {
    circuitBreakers.circuitBreaker("CLIENT009").transitionToOpenState();

    assertThat(
            meterRegistry
                .get("resilience4j.circuitbreaker.state")
                .tag("name", "CLIENT009")
                .tag("state", "open")
                .gauge()
                .value())
        .isEqualTo(1);
  }

  @Test
  void endToEndLatencyBucketsReachTwoHours() {
    assertThat(bucketsInMillis("notificator.delivery.e2e"))
        .startsWith(100, 500, 1000)
        .endsWith(Duration.ofHours(1).toMillis(), Duration.ofHours(2).toMillis());
  }

  private long[] bucketsInMillis(String timer) {
    CountAtBucket[] buckets = meterRegistry.timer(timer).takeSnapshot().histogramCounts();
    return Arrays.stream(buckets).mapToLong(b -> (long) b.bucket(TimeUnit.MILLISECONDS)).toArray();
  }
}
