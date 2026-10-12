package com.cobre.notificator.domain.port.out;

import com.cobre.notificator.domain.model.AttemptRecord;
import com.cobre.notificator.domain.model.DeliveryStatus;
import com.cobre.notificator.domain.model.NotificationEvent;
import com.cobre.notificator.domain.model.ReplayRequest;
import com.cobre.notificator.domain.model.TransactionEvent;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Every write is conditional and returns whether it changed the row: {@code false} means another
 * process moved the event first, and the caller must discard its result. Every UPDATE also requires
 * the event to still be at {@code generation}: (generation, attempts) is the fencing token. A row
 * is identified by (event_id, client_id): the producer's event_id is unique only per client.
 */
public interface NotificationEventRepository {

  /**
   * Creates the event in PENDING with attempts=1 and returns its generated id; empty if the client
   * already has an event with that event_id.
   */
  Optional<String> insertClaim(TransactionEvent event);

  /**
   * FAILED -> PENDING at generation + 1 and attempts=1, only for the owner and the generation the
   * request was made on.
   */
  boolean claimReplay(ReplayRequest request);

  /** attempts: attempt-1 -> attempt, only while PENDING or RETRYING. */
  boolean claimAttempt(String eventId, String clientId, int generation, int attempt);

  Optional<NotificationEvent> findById(String eventId, String clientId);

  /** Success always wins: any attempt, while PENDING or RETRYING. */
  boolean markDelivered(String eventId, String clientId, int generation, AttemptRecord record);

  /**
   * RETRYING, FAILED or NOT_SUBSCRIBED, only if {@code attempt} is still current and non-final.
   *
   * @param record null keeps the last attempt data unchanged
   */
  boolean recordAttempt(
      String eventId,
      String clientId,
      int generation,
      int attempt,
      DeliveryStatus status,
      AttemptRecord record);

  /** PENDING or RETRYING events not updated for longer than {@code minAge}, per DB time. */
  List<NotificationEvent> findStale(Duration minAge, int limit);

  /** Touches updated_at if the event is still at {@code attempt} and older than {@code age}. */
  boolean refreshIfStale(
      String eventId, String clientId, int generation, int attempt, Duration age);

  /** FAILED if the event is still at {@code attempt} and older than {@code age}. */
  boolean failIfStale(String eventId, String clientId, int generation, int attempt, Duration age);
}
