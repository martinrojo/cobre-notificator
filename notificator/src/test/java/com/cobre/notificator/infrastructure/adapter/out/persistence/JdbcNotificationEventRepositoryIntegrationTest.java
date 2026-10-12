package com.cobre.notificator.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.notificator.domain.model.AttemptRecord;
import com.cobre.notificator.domain.model.DeliveryStatus;
import com.cobre.notificator.domain.model.NotificationEvent;
import com.cobre.notificator.domain.model.ReplayRequest;
import com.cobre.notificator.domain.model.TransactionEvent;
import com.cobre.notificator.it.MariaDb;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = MariaDb.ENABLED_VAR, matches = ".+")
class JdbcNotificationEventRepositoryIntegrationTest {

  private static final AttemptRecord RECORD =
      new AttemptRecord(MariaDb.now(), "https://a.example/hook", 503, "HTTP 503");

  private final JdbcNotificationEventRepository repository =
      new JdbcNotificationEventRepository(MariaDb.notificator());

  private static final String CLIENT = "IT-CLIENT";
  private static final String OTHER = "IT-OTHER";

  private String eventId;
  private TransactionEvent event;

  @BeforeEach
  void setUp() {
    eventId = MariaDb.newId();
    event = new TransactionEvent(eventId, CLIENT, "credit_deposit", "secret", MariaDb.now());
  }

  @AfterEach
  void tearDown() {
    MariaDb.deleteEvent(eventId);
  }

  @Test
  void insertClaimOnlyOnce() {
    String id = repository.insertClaim(event).orElseThrow();
    assertThat(repository.insertClaim(event)).isEmpty();

    assertThat(UUID.fromString(id).version()).isEqualTo(7);
    NotificationEvent row = repository.findById(eventId, CLIENT).orElseThrow();
    assertThat(row.id()).isEqualTo(id);
    assertThat(row.deliveryStatus()).isEqualTo(DeliveryStatus.PENDING);
    assertThat(row.attempts()).isEqualTo(1);
    assertThat(row.createdAt()).isEqualTo(MariaDb.now());
    assertThat(row.generation()).isZero();
    assertThat(row.lastReplayAt()).isNull();
  }

  @Test
  void claimAttemptOnlyOnce() {
    repository.insertClaim(event);

    assertThat(repository.claimAttempt(eventId, CLIENT, 0, 2)).isTrue();
    assertThat(repository.claimAttempt(eventId, CLIENT, 0, 2)).isFalse();
    assertThat(repository.findById(eventId, CLIENT).orElseThrow().attempts()).isEqualTo(2);
  }

  @Test
  void recordAttemptWithStaleAttemptDoesNothing() {
    repository.insertClaim(event);
    repository.claimAttempt(eventId, CLIENT, 0, 2);

    assertThat(repository.recordAttempt(eventId, CLIENT, 0, 1, DeliveryStatus.RETRYING, RECORD))
        .isFalse();
    assertThat(repository.findById(eventId, CLIENT).orElseThrow().deliveryStatus())
        .isEqualTo(DeliveryStatus.PENDING);

    assertThat(repository.recordAttempt(eventId, CLIENT, 0, 2, DeliveryStatus.RETRYING, RECORD))
        .isTrue();
    assertThat(repository.findById(eventId, CLIENT).orElseThrow().deliveryStatus())
        .isEqualTo(DeliveryStatus.RETRYING);
  }

  @Test
  void deliveredWinsOverAnyAttempt() {
    repository.insertClaim(event);
    repository.claimAttempt(eventId, CLIENT, 0, 2);

    assertThat(repository.markDelivered(eventId, CLIENT, 0, RECORD)).isTrue();
    assertThat(repository.findById(eventId, CLIENT).orElseThrow().deliveryStatus())
        .isEqualTo(DeliveryStatus.DELIVERED);
  }

  @Test
  void deliveredDoesNotOverrideFinalStatus() {
    repository.insertClaim(event);
    repository.recordAttempt(eventId, CLIENT, 0, 1, DeliveryStatus.FAILED, RECORD);

    assertThat(repository.markDelivered(eventId, CLIENT, 0, RECORD)).isFalse();
    assertThat(repository.findById(eventId, CLIENT).orElseThrow().deliveryStatus())
        .isEqualTo(DeliveryStatus.FAILED);
  }

  @Test
  void recordAttemptTruncatesError() {
    repository.insertClaim(event);
    AttemptRecord longError = new AttemptRecord(MariaDb.now(), null, null, "e".repeat(600));

    assertThat(repository.recordAttempt(eventId, CLIENT, 0, 1, DeliveryStatus.RETRYING, longError))
        .isTrue();
    assertThat(
            MariaDb.root()
                .sql("SELECT CHAR_LENGTH(last_error) FROM notification_event WHERE event_id = ?")
                .param(eventId)
                .query(Integer.class)
                .single())
        .isEqualTo(500);
  }

  @Test
  void refreshIfStaleOnlyOnce() {
    repository.insertClaim(event);
    MariaDb.ageEvent(eventId, CLIENT, 200);

    assertThat(repository.findStale(Duration.ofSeconds(120), 1000))
        .extracting(NotificationEvent::eventId)
        .contains(eventId);
    assertThat(repository.refreshIfStale(eventId, CLIENT, 0, 1, Duration.ofSeconds(180))).isTrue();
    assertThat(repository.refreshIfStale(eventId, CLIENT, 0, 1, Duration.ofSeconds(180))).isFalse();
  }

  @Test
  void eventWithinThresholdIsNotTouched() {
    repository.insertClaim(event);
    MariaDb.ageEvent(eventId, CLIENT, 60);

    assertThat(repository.findStale(Duration.ofSeconds(120), 1000))
        .extracting(NotificationEvent::eventId)
        .doesNotContain(eventId);
    assertThat(repository.refreshIfStale(eventId, CLIENT, 0, 1, Duration.ofSeconds(180))).isFalse();
    assertThat(repository.failIfStale(eventId, CLIENT, 0, 1, Duration.ofSeconds(120))).isFalse();
  }

  @Test
  void failIfStaleRequiresCurrentAttempt() {
    repository.insertClaim(event);
    MariaDb.ageEvent(eventId, CLIENT, 200);

    assertThat(repository.failIfStale(eventId, CLIENT, 0, 4, Duration.ofSeconds(120))).isFalse();
    assertThat(repository.failIfStale(eventId, CLIENT, 0, 1, Duration.ofSeconds(120))).isTrue();
    assertThat(repository.findById(eventId, CLIENT).orElseThrow().deliveryStatus())
        .isEqualTo(DeliveryStatus.FAILED);
  }

  @Test
  void claimReplayReopensFailedEventOnce() {
    failed();
    Instant requestedAt = Instant.parse("2026-10-10T12:00:00.123Z");

    assertThat(repository.claimReplay(replay(CLIENT, 0, requestedAt))).isTrue();
    // Same generation again: the duplicate request loses.
    assertThat(repository.claimReplay(replay(CLIENT, 0, requestedAt))).isFalse();

    NotificationEvent row = repository.findById(eventId, CLIENT).orElseThrow();
    assertThat(row.deliveryStatus()).isEqualTo(DeliveryStatus.PENDING);
    assertThat(row.generation()).isEqualTo(1);
    assertThat(row.attempts()).isEqualTo(1);
    assertThat(row.lastReplayAt()).isEqualTo(requestedAt);
  }

  @Test
  void claimReplayRequiresFailedOwnerAndGeneration() {
    repository.insertClaim(event);
    assertThat(repository.claimReplay(replay(CLIENT, 0, MariaDb.now()))).isFalse(); // PENDING

    repository.recordAttempt(eventId, CLIENT, 0, 1, DeliveryStatus.FAILED, RECORD);
    assertThat(repository.claimReplay(replay(OTHER, 0, MariaDb.now()))).isFalse();
    assertThat(repository.claimReplay(replay(CLIENT, 1, MariaDb.now()))).isFalse();
    assertThat(repository.claimReplay(new ReplayRequest("IT-MISSING", CLIENT, 0, MariaDb.now())))
        .isFalse();

    NotificationEvent row = repository.findById(eventId, CLIENT).orElseThrow();
    assertThat(row.deliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
    assertThat(row.generation()).isZero();
  }

  @Test
  void writesOfAnotherGenerationDoNothingEvenWithMatchingAttempts() {
    failed();
    repository.claimReplay(replay(CLIENT, 0, MariaDb.now())); // generation 1, attempts 1
    MariaDb.ageEvent(eventId, CLIENT, 200);

    // A late generation-0 message expecting attempts=1 must not match the new generation.
    assertThat(repository.claimAttempt(eventId, CLIENT, 0, 2)).isFalse();
    assertThat(repository.recordAttempt(eventId, CLIENT, 0, 1, DeliveryStatus.RETRYING, RECORD))
        .isFalse();
    assertThat(repository.recordAttempt(eventId, CLIENT, 0, 1, DeliveryStatus.FAILED, null))
        .isFalse();
    assertThat(repository.markDelivered(eventId, CLIENT, 0, RECORD)).isFalse();
    assertThat(repository.refreshIfStale(eventId, CLIENT, 0, 1, Duration.ofSeconds(120))).isFalse();
    assertThat(repository.failIfStale(eventId, CLIENT, 0, 1, Duration.ofSeconds(120))).isFalse();
    NotificationEvent row = repository.findById(eventId, CLIENT).orElseThrow();
    assertThat(row.deliveryStatus()).isEqualTo(DeliveryStatus.PENDING);
    assertThat(row.attempts()).isEqualTo(1);

    assertThat(repository.claimAttempt(eventId, CLIENT, 1, 2)).isTrue();
    assertThat(repository.markDelivered(eventId, CLIENT, 1, RECORD)).isTrue();
  }

  @Test
  void sameEventIdOfAnotherClientIsAnotherEvent() {
    String id = repository.insertClaim(event).orElseThrow();
    String otherId = repository.insertClaim(otherClientEvent()).orElseThrow();

    assertThat(otherId).isNotEqualTo(id);
    assertThat(repository.findById(eventId, OTHER).orElseThrow().id()).isEqualTo(otherId);
  }

  @Test
  void writesNeverTouchAnotherClientsEventWithTheSameEventId() {
    repository.insertClaim(event);
    repository.insertClaim(otherClientEvent());
    MariaDb.ageEvent(eventId, CLIENT, 200);
    MariaDb.ageEvent(eventId, OTHER, 200);
    NotificationEvent before = repository.findById(eventId, OTHER).orElseThrow();

    // Each write succeeds on CLIENT's row, so a missing client_id filter would also hit OTHER's.
    assertThat(repository.refreshIfStale(eventId, CLIENT, 0, 1, Duration.ofSeconds(120))).isTrue();
    assertThat(repository.claimAttempt(eventId, CLIENT, 0, 2)).isTrue();
    assertThat(repository.recordAttempt(eventId, CLIENT, 0, 2, DeliveryStatus.RETRYING, RECORD))
        .isTrue();
    assertThat(repository.markDelivered(eventId, CLIENT, 0, RECORD)).isTrue();
    assertThat(repository.findById(eventId, OTHER).orElseThrow()).isEqualTo(before);
  }

  @Test
  void failWritesNeverTouchAnotherClientsEventWithTheSameEventId() {
    repository.insertClaim(event);
    repository.insertClaim(otherClientEvent());
    MariaDb.ageEvent(eventId, CLIENT, 200);
    NotificationEvent before = repository.findById(eventId, OTHER).orElseThrow();

    assertThat(repository.failIfStale(eventId, CLIENT, 0, 1, Duration.ofSeconds(120))).isTrue();
    assertThat(repository.claimReplay(replay(CLIENT, 0, MariaDb.now()))).isTrue();
    assertThat(repository.recordAttempt(eventId, CLIENT, 1, 1, DeliveryStatus.FAILED, null))
        .isTrue();
    assertThat(repository.findById(eventId, OTHER).orElseThrow()).isEqualTo(before);
  }

  private TransactionEvent otherClientEvent() {
    return new TransactionEvent(eventId, OTHER, "credit_deposit", "other", MariaDb.now());
  }

  private void failed() {
    repository.insertClaim(event);
    repository.recordAttempt(eventId, CLIENT, 0, 1, DeliveryStatus.FAILED, RECORD);
  }

  private ReplayRequest replay(String clientId, int generation, Instant requestedAt) {
    return new ReplayRequest(eventId, clientId, generation, requestedAt);
  }
}
