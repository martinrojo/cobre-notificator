package com.cobre.notificator.domain.model;

public enum DeliveryOutcome {
  DELIVERED,
  RETRYABLE,
  PERMANENT;

  /**
   * Retry only what may succeed later: no response, 408, 429 and 5xx. Redirects are not followed.
   */
  public static DeliveryOutcome of(WebhookResult result) {
    return switch (result) {
      case WebhookResult.TransportError e -> RETRYABLE;
      case WebhookResult.CircuitOpen c -> RETRYABLE;
      case WebhookResult.Responded(int status) when status >= 200 && status < 300 -> DELIVERED;
      case WebhookResult.Responded(int status)
          when status == 408 || status == 429 || status >= 500 ->
          RETRYABLE;
      case WebhookResult.Responded r -> PERMANENT;
    };
  }
}
