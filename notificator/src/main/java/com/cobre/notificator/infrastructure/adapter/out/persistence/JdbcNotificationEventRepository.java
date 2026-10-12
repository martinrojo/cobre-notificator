package com.cobre.notificator.infrastructure.adapter.out.persistence;

import com.cobre.notificator.domain.model.AttemptRecord;
import com.cobre.notificator.domain.model.DeliveryStatus;
import com.cobre.notificator.domain.model.NotificationEvent;
import com.cobre.notificator.domain.model.ReplayRequest;
import com.cobre.notificator.domain.model.TransactionEvent;
import com.cobre.notificator.domain.port.out.NotificationEventRepository;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The WHERE clause of each UPDATE is the compare-and-set: the MariaDB driver reports matched rows,
 * so 1 means this process won and 0 means another one moved the event first. Staleness uses DB time
 * (NOW(3) against updated_at), so clock drift between instances does not matter.
 */
@Repository
public class JdbcNotificationEventRepository implements NotificationEventRepository {

  private static final String NON_FINAL = "delivery_status IN ('PENDING', 'RETRYING')";

  // A row is (client_id, event_id): event_id alone may match another client's event.
  private static final String ROW = "client_id = :clientId AND event_id = :eventId";

  private static final String CURRENT = ROW + " AND generation = :generation AND ";

  private static final String CLAIM_REPLAY =
      """
      UPDATE notification_event
      SET delivery_status = 'PENDING', generation = generation + 1, attempts = 1,
          last_replay_at = :requestedAt
      WHERE client_id = :clientId AND event_id = :eventId AND delivery_status = 'FAILED'
        AND generation = :generation
      """;

  private static final String INSERT_CLAIM =
      """
      INSERT INTO notification_event
          (event_id, client_id, event_type, content, delivery_status, attempts, created_at)
      VALUES (:eventId, :clientId, :eventType, :content, 'PENDING', 1, :createdAt)
      RETURNING id
      """;

  private static final String CLAIM_ATTEMPT =
      "UPDATE notification_event SET attempts = :attempt"
          + " WHERE "
          + CURRENT
          + "attempts = :attempt - 1 AND "
          + NON_FINAL;

  private static final String SELECT =
      """
      SELECT id, event_id, client_id, event_type, content, delivery_status, attempts, created_at,
             generation, last_replay_at
      FROM notification_event
      """;

  private static final String MARK_DELIVERED =
      """
      UPDATE notification_event
      SET delivery_status = 'DELIVERED', last_attempt_at = :attemptedAt, delivery_url = :url,
          last_http_status = :httpStatus, last_error = NULL
      WHERE\s"""
          + CURRENT
          + NON_FINAL;

  private static final String RECORD_ATTEMPT =
      """
      UPDATE notification_event
      SET delivery_status = :status, last_attempt_at = :attemptedAt, delivery_url = :url,
          last_http_status = :httpStatus, last_error = :error
      WHERE\s"""
          + CURRENT
          + "attempts = :attempt AND "
          + NON_FINAL;

  private static final String SET_STATUS =
      "UPDATE notification_event SET delivery_status = :status"
          + " WHERE "
          + CURRENT
          + "attempts = :attempt AND "
          + NON_FINAL;

  private static final String OLDER_THAN = " updated_at < NOW(3) - INTERVAL :ageMicros MICROSECOND";

  private static final String FIND_STALE =
      SELECT + "WHERE " + NON_FINAL + " AND" + OLDER_THAN + " ORDER BY updated_at LIMIT :limit";

  private static final String REFRESH_IF_STALE =
      "UPDATE notification_event SET updated_at = NOW(3)"
          + " WHERE "
          + CURRENT
          + "attempts = :attempt AND "
          + NON_FINAL
          + " AND"
          + OLDER_THAN;

  private static final String FAIL_IF_STALE =
      "UPDATE notification_event SET delivery_status = 'FAILED'"
          + " WHERE "
          + CURRENT
          + "attempts = :attempt AND "
          + NON_FINAL
          + " AND"
          + OLDER_THAN;

  private static final int MAX_ERROR_LENGTH = 500;

  // DATETIME columns hold UTC without zone: convert explicitly, never via the JVM default zone.
  private static final RowMapper<NotificationEvent> ROW_MAPPER =
      (rs, rowNum) ->
          new NotificationEvent(
              rs.getString("id"),
              rs.getString("event_id"),
              rs.getString("client_id"),
              rs.getString("event_type"),
              rs.getString("content"),
              DeliveryStatus.valueOf(rs.getString("delivery_status")),
              rs.getInt("attempts"),
              toInstant(rs.getObject("created_at", LocalDateTime.class)),
              rs.getInt("generation"),
              toInstant(rs.getObject("last_replay_at", LocalDateTime.class)));

  private final JdbcClient jdbcClient;

  public JdbcNotificationEventRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public Optional<String> insertClaim(TransactionEvent event) {
    try {
      return Optional.of(
          jdbcClient
              .sql(INSERT_CLAIM)
              .param("eventId", event.eventId())
              .param("clientId", event.clientId())
              .param("eventType", event.eventType())
              .param("content", event.content())
              .param("createdAt", toUtc(event.createdAt()))
              .query(String.class)
              .single());
    } catch (DuplicateKeyException e) {
      return Optional.empty();
    }
  }

  @Override
  public boolean claimReplay(ReplayRequest request) {
    return jdbcClient
            .sql(CLAIM_REPLAY)
            .param("eventId", request.eventId())
            .param("clientId", request.clientId())
            .param("generation", request.generation())
            .param("requestedAt", toUtc(request.requestedAt()))
            .update()
        == 1;
  }

  @Override
  public boolean claimAttempt(String eventId, String clientId, int generation, int attempt) {
    return jdbcClient
            .sql(CLAIM_ATTEMPT)
            .param("eventId", eventId)
            .param("clientId", clientId)
            .param("generation", generation)
            .param("attempt", attempt)
            .update()
        == 1;
  }

  @Override
  public Optional<NotificationEvent> findById(String eventId, String clientId) {
    return jdbcClient
        .sql(SELECT + "WHERE " + ROW)
        .param("eventId", eventId)
        .param("clientId", clientId)
        .query(ROW_MAPPER)
        .optional();
  }

  @Override
  public boolean markDelivered(
      String eventId, String clientId, int generation, AttemptRecord record) {
    return jdbcClient
            .sql(MARK_DELIVERED)
            .param("eventId", eventId)
            .param("clientId", clientId)
            .param("generation", generation)
            .param("attemptedAt", toUtc(record.attemptedAt()))
            .param("url", record.deliveryUrl(), Types.VARCHAR)
            .param("httpStatus", record.httpStatus(), Types.SMALLINT)
            .update()
        == 1;
  }

  @Override
  public boolean recordAttempt(
      String eventId,
      String clientId,
      int generation,
      int attempt,
      DeliveryStatus status,
      AttemptRecord record) {
    if (record == null) {
      return jdbcClient
              .sql(SET_STATUS)
              .param("eventId", eventId)
              .param("clientId", clientId)
              .param("generation", generation)
              .param("attempt", attempt)
              .param("status", status.name())
              .update()
          == 1;
    }
    return jdbcClient
            .sql(RECORD_ATTEMPT)
            .param("eventId", eventId)
            .param("clientId", clientId)
            .param("generation", generation)
            .param("attempt", attempt)
            .param("status", status.name())
            .param("attemptedAt", toUtc(record.attemptedAt()))
            .param("url", record.deliveryUrl(), Types.VARCHAR)
            .param("httpStatus", record.httpStatus(), Types.SMALLINT)
            .param("error", truncate(record.error()), Types.VARCHAR)
            .update()
        == 1;
  }

  @Override
  public List<NotificationEvent> findStale(Duration minAge, int limit) {
    return jdbcClient
        .sql(FIND_STALE)
        .param("ageMicros", toMicros(minAge))
        .param("limit", limit)
        .query(ROW_MAPPER)
        .list();
  }

  @Override
  public boolean refreshIfStale(
      String eventId, String clientId, int generation, int attempt, Duration age) {
    return staleUpdate(REFRESH_IF_STALE, eventId, clientId, generation, attempt, age);
  }

  @Override
  public boolean failIfStale(
      String eventId, String clientId, int generation, int attempt, Duration age) {
    return staleUpdate(FAIL_IF_STALE, eventId, clientId, generation, attempt, age);
  }

  private boolean staleUpdate(
      String sql, String eventId, String clientId, int generation, int attempt, Duration age) {
    return jdbcClient
            .sql(sql)
            .param("eventId", eventId)
            .param("clientId", clientId)
            .param("generation", generation)
            .param("attempt", attempt)
            .param("ageMicros", toMicros(age))
            .update()
        == 1;
  }

  private static long toMicros(Duration duration) {
    return duration.toNanos() / 1_000;
  }

  private static LocalDateTime toUtc(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static Instant toInstant(LocalDateTime value) {
    return value == null ? null : value.toInstant(ZoneOffset.UTC);
  }

  private static String truncate(String error) {
    return error == null || error.length() <= MAX_ERROR_LENGTH
        ? error
        : error.substring(0, MAX_ERROR_LENGTH);
  }
}
