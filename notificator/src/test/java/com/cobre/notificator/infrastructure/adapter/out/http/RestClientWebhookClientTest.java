package com.cobre.notificator.infrastructure.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.notificator.config.DeliveryProperties;
import com.cobre.notificator.config.WebhookHttpConfig;
import com.cobre.notificator.domain.model.TransactionEvent;
import com.cobre.notificator.domain.model.WebhookResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class RestClientWebhookClientTest {

  private static final String ID = "01a1311c-4907-792c-a433-4e7984227e77";
  private static final TransactionEvent EVENT =
      new TransactionEvent(
          "EVT100",
          "CLIENT001",
          "credit_deposit",
          "Deposit of $10",
          Instant.parse("2026-10-11T10:00:00Z"));
  private static final TransactionEvent OTHER_CLIENT_EVENT =
      new TransactionEvent(
          "EVT200",
          "CLIENT002",
          "credit_deposit",
          "Deposit of $20",
          Instant.parse("2026-10-11T10:00:00Z"));

  /** Small window: 4 calls decide, 2 at least; short open wait; one half-open probe. */
  private static final CircuitBreakerConfig BREAKER =
      CircuitBreakerConfig.custom()
          .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
          .slidingWindowSize(4)
          .minimumNumberOfCalls(4)
          .failureRateThreshold(50)
          .waitDurationInOpenState(Duration.ofMillis(50))
          .permittedNumberOfCallsInHalfOpenState(1)
          .build();

  private final AtomicReference<HttpExchange> received = new AtomicReference<>();
  private final AtomicReference<String> receivedBody = new AtomicReference<>();
  private final AtomicInteger requests = new AtomicInteger();
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final CircuitBreakerRegistry circuitBreakers = CircuitBreakerRegistry.of(BREAKER);
  private HttpServer server;
  private RestClientWebhookClient client;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/ok", exchange -> respond(exchange, 204));
    server.createContext("/not-found", exchange -> respond(exchange, 404));
    server.createContext("/unavailable", exchange -> respond(exchange, 503));
    server.createContext("/error", exchange -> respond(exchange, 500));
    server.createContext(
        "/redirect",
        exchange -> {
          exchange.getResponseHeaders().add("Location", url("/ok"));
          respond(exchange, 302);
        });
    server.createContext(
        "/slow",
        exchange -> {
          sleep(Duration.ofSeconds(2));
          respond(exchange, 200);
        });
    server.start();

    DeliveryProperties properties =
        new DeliveryProperties(
            null,
            null,
            new DeliveryProperties.Webhook(Duration.ofMillis(500), Duration.ofMillis(300), true),
            null);
    JsonMapper jsonMapper =
        JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    client =
        new RestClientWebhookClient(
            new WebhookHttpConfig().webhookRestClient(properties),
            jsonMapper,
            meterRegistry,
            circuitBreakers);
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void postsEventWithHeaders() {
    WebhookResult result = client.post(url("/ok"), ID, EVENT, 3);

    assertThat(result).isEqualTo(new WebhookResult.Responded(204));
    HttpExchange exchange = received.get();
    assertThat(exchange.getRequestMethod()).isEqualTo("POST");
    assertThat(exchange.getRequestHeaders().getFirst("X-Notification-Event-Id")).isEqualTo(ID);
    assertThat(exchange.getRequestHeaders().getFirst("X-Event-Id")).isEqualTo("EVT100");
    assertThat(exchange.getRequestHeaders().getFirst("X-Delivery-Attempt")).isEqualTo("3");
    assertThat(exchange.getRequestHeaders().getFirst("Content-Type"))
        .startsWith("application/json");
    assertThat(receivedBody.get())
        .isEqualTo(
            "{\"notification_event_id\":\""
                + ID
                + "\",\"event_id\":\"EVT100\",\"client_id\":\"CLIENT001\",\"event_type\":\"credit_deposit\","
                + "\"content\":\"Deposit of $10\",\"created_at\":\"2026-10-11T10:00:00Z\"}");
    assertThat(
            meterRegistry
                .get(RestClientWebhookClient.LATENCY)
                .tag("client_id", "CLIENT001")
                .tag("outcome", "delivered")
                .timer()
                .count())
        .isEqualTo(1);
    assertThat(attempts("3", "204", "delivered")).isEqualTo(1);
  }

  @Test
  void notFoundIsCountedAsPermanent() {
    client.post(url("/not-found"), ID, EVENT, 1);

    assertThat(attempts("1", "404", "permanent")).isEqualTo(1);
  }

  @Test
  void unavailableIsCountedAsRetryable() {
    client.post(url("/unavailable"), ID, EVENT, 2);

    assertThat(attempts("2", "503", "retryable")).isEqualTo(1);
  }

  @Test
  void metricTagsNeverHoldTheUrlOrTheBody() {
    client.post(url("/ok"), ID, EVENT, 1);
    client.post(url("/unavailable"), ID, EVENT, 2);

    assertThat(meterRegistry.getMeters())
        .flatExtracting(m -> m.getId().getTags())
        .extracting(tag -> tag.getValue())
        .noneMatch(v -> v.contains("localhost") || v.contains("Deposit"));
  }

  @Test
  void doesNotFollowRedirects() {
    assertThat(client.post(url("/redirect"), ID, EVENT, 1))
        .isEqualTo(new WebhookResult.Responded(302));
    assertThat(received.get().getRequestURI().getPath()).isEqualTo("/redirect");
  }

  @Test
  void slowResponseIsTransportError() {
    assertThat(client.post(url("/slow"), ID, EVENT, 1))
        .isInstanceOf(WebhookResult.TransportError.class);
    assertThat(attempts("1", "timeout", "retryable")).isEqualTo(1);
  }

  @Test
  void closedPortIsTransportError() throws IOException {
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    assertThat(client.post("http://localhost:" + port + "/hook", ID, EVENT, 1))
        .isInstanceOf(WebhookResult.TransportError.class);
    assertThat(attempts("1", "connect_error", "retryable")).isEqualTo(1);
  }

  @Test
  void unknownTransportErrorIsBounded() {
    assertThat(RestClientWebhookClient.status(new WebhookResult.TransportError("SSLException")))
        .isEqualTo("io_error");
  }

  @Test
  void repeatedUnavailableOpensTheCircuitAndStopsPosting() {
    postTimes("/unavailable", EVENT, 4);
    int posted = requests.get();

    assertThat(client.post(url("/ok"), ID, EVENT, 1)).isEqualTo(new WebhookResult.CircuitOpen());
    assertThat(requests.get()).isEqualTo(posted);
    assertThat(state("CLIENT001")).isEqualTo(CircuitBreaker.State.OPEN);
  }

  @Test
  void repeatedReadTimeoutsOpenTheCircuit() {
    postTimes("/slow", EVENT, 4);

    assertThat(state("CLIENT001")).isEqualTo(CircuitBreaker.State.OPEN);
  }

  @Test
  void repeatedInternalErrorsKeepTheCircuitClosed() {
    postTimes("/error", EVENT, 6);

    assertThat(requests.get()).isEqualTo(6);
    assertThat(state("CLIENT001")).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  void repeatedNotFoundKeepsTheCircuitClosed() {
    postTimes("/not-found", EVENT, 6);

    assertThat(state("CLIENT001")).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  void openCircuitOfOneClientDoesNotStopAnother() {
    postTimes("/unavailable", OTHER_CLIENT_EVENT, 4);
    assertThat(state("CLIENT002")).isEqualTo(CircuitBreaker.State.OPEN);

    assertThat(client.post(url("/ok"), ID, EVENT, 1)).isEqualTo(new WebhookResult.Responded(204));
    assertThat(state("CLIENT001")).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  void successfulProbeAfterTheWaitClosesTheCircuit() {
    postTimes("/unavailable", EVENT, 4);
    sleep(Duration.ofMillis(100));

    assertThat(client.post(url("/ok"), ID, EVENT, 2)).isEqualTo(new WebhookResult.Responded(204));
    assertThat(state("CLIENT001")).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  void unexpectedErrorInHalfOpenReturnsThePermitUncounted() {
    circuitBreakers.circuitBreaker("CLIENT001").transitionToOpenState();
    circuitBreakers.circuitBreaker("CLIENT001").transitionToHalfOpenState();

    assertThatThrownBy(() -> client.post("not a url", ID, EVENT, 1))
        .isInstanceOf(RuntimeException.class);

    // The only probe permit came back: the next call is made, and closes the circuit.
    assertThat(client.post(url("/ok"), ID, EVENT, 1)).isEqualTo(new WebhookResult.Responded(204));
    assertThat(state("CLIENT001")).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  void circuitOpenIsCountedWithoutLatency() {
    circuitBreakers.circuitBreaker("CLIENT001").transitionToOpenState();

    client.post(url("/ok"), ID, EVENT, 2);

    assertThat(attempts("2", "circuit_open", "retryable")).isEqualTo(1);
    assertThat(meterRegistry.find(RestClientWebhookClient.LATENCY).timers()).isEmpty();
    assertThat(requests.get()).isZero();
  }

  private void postTimes(String path, TransactionEvent event, int times) {
    for (int i = 0; i < times; i++) {
      client.post(url(path), ID, event, 1);
    }
  }

  private CircuitBreaker.State state(String clientId) {
    return circuitBreakers.circuitBreaker(clientId).getState();
  }

  private double attempts(String attempt, String status, String outcome) {
    return meterRegistry
        .get(RestClientWebhookClient.ATTEMPTS)
        .tag("client_id", "CLIENT001")
        .tag("attempt", attempt)
        .tag("status", status)
        .tag("outcome", outcome)
        .counter()
        .count();
  }

  private void respond(HttpExchange exchange, int status) throws IOException {
    requests.incrementAndGet();
    if (received.get() == null) {
      received.set(exchange);
      receivedBody.set(
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }
    exchange.sendResponseHeaders(status, -1);
    exchange.close();
  }

  private String url(String path) {
    return "http://localhost:" + server.getAddress().getPort() + path;
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
