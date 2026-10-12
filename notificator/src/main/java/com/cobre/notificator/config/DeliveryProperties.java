package com.cobre.notificator.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param lease how long an attempt may stay in flight before the recovery job resumes the event;
 *     must exceed the webhook connect + read timeout plus the expected consumer lag
 */
@ConfigurationProperties("notificator.delivery")
public record DeliveryProperties(Retry retry, Duration lease, Webhook webhook, Recovery recovery) {

  /** Non-blocking retries: attempts = 1 initial + retries; delay(k) = delay * multiplier^(k-1). */
  public record Retry(int attempts, Duration delay, double multiplier) {}

  public record Webhook(Duration connectTimeout, Duration readTimeout, boolean allowHttp) {}

  public record Recovery(Duration interval, int batchSize) {}
}
