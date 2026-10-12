package com.cobre.notificator.infrastructure.adapter.out.http;

import com.cobre.notificator.domain.model.DeliveryOutcome;
import com.cobre.notificator.domain.model.TransactionEvent;
import com.cobre.notificator.domain.model.WebhookResult;
import com.cobre.notificator.domain.port.out.WebhookClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redirect and timeout policy live in {@code WebhookHttpConfig}.
 *
 * <p>Each client has its own circuit breaker, so an unavailable client stops holding the partition
 * thread it shares with others. Results are reported by hand because {@link #post} never throws.
 */
@Component
public class RestClientWebhookClient implements WebhookClient {

  static final String NOTIFICATION_EVENT_ID_HEADER = "X-Notification-Event-Id";
  static final String EVENT_ID_HEADER = "X-Event-Id";
  static final String ATTEMPT_HEADER = "X-Delivery-Attempt";
  static final String LATENCY = "notificator.webhook.latency";
  static final String ATTEMPTS = "notificator.webhook.attempts";

  /** Root causes of a refused or unresolvable connection in the JDK HttpClient. */
  private static final Set<String> CONNECT_ERRORS =
      Set.of("ConnectException", "ClosedChannelException", "UnresolvedAddressException");

  /** Fed to the breaker for an unavailable result; carries no data and no stack trace. */
  private static final Exception UNAVAILABLE = new WebhookUnavailable();

  private final RestClient restClient;
  private final JsonMapper jsonMapper;
  private final MeterRegistry meterRegistry;
  private final CircuitBreakerRegistry circuitBreakers;

  public RestClientWebhookClient(
      RestClient webhookRestClient,
      JsonMapper jsonMapper,
      MeterRegistry meterRegistry,
      CircuitBreakerRegistry circuitBreakers) {
    this.restClient = webhookRestClient;
    this.jsonMapper = jsonMapper;
    this.meterRegistry = meterRegistry;
    this.circuitBreakers = circuitBreakers;
  }

  @Override
  public WebhookResult post(
      String url, String notificationEventId, TransactionEvent event, int attempt) {
    String body = jsonMapper.writeValueAsString(WebhookPayload.of(notificationEventId, event));
    CircuitBreaker breaker = circuitBreakers.circuitBreaker(event.clientId());
    WebhookResult result =
        breaker.tryAcquirePermission()
            ? call(breaker, url, notificationEventId, event, attempt, body)
            : new WebhookResult.CircuitOpen();
    meterRegistry
        .counter(
            ATTEMPTS,
            "client_id",
            event.clientId(),
            "attempt",
            String.valueOf(attempt),
            "status",
            status(result),
            "outcome",
            outcome(result))
        .increment();
    return result;
  }

  /** With a permit: POST, then report to the breaker. Latency only for calls actually made. */
  private WebhookResult call(
      CircuitBreaker breaker,
      String url,
      String notificationEventId,
      TransactionEvent event,
      int attempt,
      String body) {
    Timer.Sample sample = Timer.start(meterRegistry);
    WebhookResult result;
    try {
      result = send(url, notificationEventId, event.eventId(), attempt, body);
    } catch (RuntimeException e) {
      // Our failure, not the client's: return the permit uncounted, or half-open would wait on it.
      breaker.releasePermission();
      throw e;
    }
    long nanos =
        sample.stop(
            meterRegistry.timer(
                LATENCY, "client_id", event.clientId(), "outcome", outcome(result)));
    if (result.isUnavailable()) {
      breaker.onError(nanos, TimeUnit.NANOSECONDS, UNAVAILABLE);
    } else {
      breaker.onSuccess(nanos, TimeUnit.NANOSECONDS);
    }
    return result;
  }

  private static String outcome(WebhookResult result) {
    return DeliveryOutcome.of(result).name().toLowerCase(Locale.ROOT);
  }

  /** HTTP code, or a bounded name for a transport error: never the raw exception name. */
  static String status(WebhookResult result) {
    return switch (result) {
      case WebhookResult.Responded(int status) -> String.valueOf(status);
      case WebhookResult.TransportError(String reason) when reason.endsWith("TimeoutException") ->
          "timeout";
      case WebhookResult.TransportError(String reason) when CONNECT_ERRORS.contains(reason) ->
          "connect_error";
      case WebhookResult.TransportError e -> "io_error";
      case WebhookResult.CircuitOpen c -> "circuit_open";
    };
  }

  private WebhookResult send(
      String url, String notificationEventId, String eventId, int attempt, String body) {
    try {
      int status =
          restClient
              .post()
              .uri(url)
              .contentType(MediaType.APPLICATION_JSON)
              .header(NOTIFICATION_EVENT_ID_HEADER, notificationEventId)
              .header(EVENT_ID_HEADER, eventId)
              .header(ATTEMPT_HEADER, String.valueOf(attempt))
              .body(body)
              .exchange((request, response) -> response.getStatusCode().value());
      return new WebhookResult.Responded(status);
    } catch (ResourceAccessException e) {
      // Timeout or connection error. Only the cause type: messages may echo request data.
      return new WebhookResult.TransportError(e.getMostSpecificCause().getClass().getSimpleName());
    }
  }

  private static final class WebhookUnavailable extends Exception {
    WebhookUnavailable() {
      super("Webhook unavailable", null, false, false);
    }
  }
}
