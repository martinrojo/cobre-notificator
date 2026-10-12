package com.cobre.notificator.domain.model;

import java.time.Duration;

/**
 * Backoff of the non-blocking retries.
 *
 * @param attempts total attempts, the first one included
 */
public record RetrySchedule(int attempts, Duration delay, double multiplier) {

  /** Wait after attempt {@code k} fails before attempt k+1 starts; zero after the last one. */
  public Duration delayAfter(int k) {
    if (k >= attempts) {
      return Duration.ZERO;
    }
    return Duration.ofMillis(Math.round(delay.toMillis() * Math.pow(multiplier, k - 1)));
  }
}
