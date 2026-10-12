package com.cobre.manager.infrastructure.adapter.out.persistence;

import com.cobre.manager.domain.model.DeliveryStatus;
import com.cobre.manager.domain.model.NotificationEvent;
import com.cobre.manager.domain.model.NotificationEventFilter;
import com.cobre.manager.domain.model.PageCursor;
import com.cobre.manager.domain.port.out.NotificationEventRepository;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcNotificationEventRepository implements NotificationEventRepository {

  private static final String SELECT =
      """
      SELECT id, event_id, client_id, event_type, content, delivery_status, created_at, last_attempt_at,
             last_replay_at, generation
      FROM notification_event
      """;

  // Keyset on (created_at, id): created_at comes from the producer and can tie, id cannot. The
  // expanded predicate (not a row comparison) keeps it a range scan on idx_event_client_created /
  // idx_event_client_status_created, which carry the PK as their last column.
  private static final String FIND_ALL =
      SELECT
          + """
      WHERE client_id = :clientId
        AND (:from IS NULL OR created_at >= :from)
        AND (:to IS NULL OR created_at <= :to)
        AND (:status IS NULL OR delivery_status = :status)
        AND (:afterCreatedAt IS NULL
             OR created_at < :afterCreatedAt
             OR (created_at = :afterCreatedAt AND id < :afterId))
      ORDER BY created_at DESC, id DESC
      LIMIT :limit
      """;

  // client_id in the same query: another client's event is simply not found (no IDOR).
  private static final String FIND_BY_ID = SELECT + "WHERE id = :id AND client_id = :clientId";

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
              toInstant(rs.getObject("created_at", LocalDateTime.class)),
              toInstant(rs.getObject("last_attempt_at", LocalDateTime.class)),
              toInstant(rs.getObject("last_replay_at", LocalDateTime.class)),
              rs.getInt("generation"));

  private static final int CANONICAL_ID_LENGTH = 36;

  private final JdbcClient jdbcClient;

  public JdbcNotificationEventRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public List<NotificationEvent> findAll(
      NotificationEventFilter filter, PageCursor after, int limit) {
    return jdbcClient
        .sql(FIND_ALL)
        .param("clientId", filter.clientId())
        .param("from", toUtc(filter.from()), Types.TIMESTAMP)
        .param("to", toUtc(filter.to()), Types.TIMESTAMP)
        .param(
            "status",
            filter.deliveryStatus() == null ? null : filter.deliveryStatus().name(),
            Types.VARCHAR)
        .param("afterCreatedAt", after == null ? null : toUtc(after.createdAt()), Types.TIMESTAMP)
        .param("afterId", after == null ? null : after.id(), Types.VARCHAR)
        .param("limit", limit)
        .query(ROW_MAPPER)
        .list();
  }

  @Override
  public Optional<NotificationEvent> findById(String clientId, String id) {
    return canonicalId(id)
        .flatMap(
            canonical ->
                jdbcClient
                    .sql(FIND_BY_ID)
                    .param("id", canonical)
                    .param("clientId", clientId)
                    .query(ROW_MAPPER)
                    .optional());
  }

  /**
   * The id in canonical form, or empty if it is not a UUID: not found, like any id of another
   * client. The length check is needed because UUID.fromString also accepts short forms.
   */
  static Optional<String> canonicalId(String id) {
    if (id == null || id.length() != CANONICAL_ID_LENGTH) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(id).toString());
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static LocalDateTime toUtc(Instant instant) {
    return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static Instant toInstant(LocalDateTime value) {
    return value == null ? null : value.toInstant(ZoneOffset.UTC);
  }
}
