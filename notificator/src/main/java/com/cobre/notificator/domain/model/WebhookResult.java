package com.cobre.notificator.domain.model;

/**
 * What the webhook call produced: an HTTP status, no response at all, or no call because the
 * client's circuit was open.
 */
public sealed interface WebhookResult {

  record Responded(int status) implements WebhookResult {}

  /** Timeout or connection error: the request may or may not have reached the client. */
  record TransportError(String reason) implements WebhookResult {}

  /** Rejected by the client's circuit breaker: no POST was made. */
  record CircuitOpen() implements WebhookResult {}

  /**
   * Whether the result says the client is unavailable, which is what opens its circuit. Not the
   * same as retryable: a 500 or 408 is a fast answer from a live client, retried but not counted.
   */
  default boolean isUnavailable() {
    return switch (this) {
      case TransportError e -> true;
      case Responded(int status) ->
          status == 429 || status == 502 || status == 503 || status == 504;
      case CircuitOpen c -> false;
    };
  }
}
