package com.cobre.manager.infrastructure.adapter.out.persistence;

import com.cobre.manager.domain.model.DeliveryStatus;
import com.cobre.manager.domain.model.NotificationEvent;
import com.cobre.manager.domain.model.NotificationEventFilter;
import com.cobre.manager.domain.port.out.NotificationEventRepository;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcNotificationEventRepository implements NotificationEventRepository {

  private static final String SELECT =
      """
      SELECT event_id, client_id, event_type, content, delivery_status, created_at, last_attempt_at,
             last_replay_at
      FROM notification_event
      """;

  private static final String FIND_ALL =
      SELECT
          + """
      WHERE client_id = :clientId
        AND (:from IS NULL OR created_at >= :from)
        AND (:to IS NULL OR created_at <= :to)
        AND (:status IS NULL OR delivery_status = :status)
      ORDER BY created_at DESC
      """;

  // client_id in the same query: another client's event is simply not found (no IDOR).
  private static final String FIND_BY_ID =
      SELECT + "WHERE event_id = :eventId AND client_id = :clientId";

  // DATETIME columns hold UTC without zone: convert explicitly, never via the JVM default zone.
  private static final RowMapper<NotificationEvent> ROW_MAPPER =
      (rs, rowNum) ->
          new NotificationEvent(
              rs.getString("event_id"),
              rs.getString("client_id"),
              rs.getString("event_type"),
              rs.getString("content"),
              DeliveryStatus.valueOf(rs.getString("delivery_status")),
              toInstant(rs.getObject("created_at", LocalDateTime.class)),
              toInstant(rs.getObject("last_attempt_at", LocalDateTime.class)),
              toInstant(rs.getObject("last_replay_at", LocalDateTime.class)));

  private final JdbcClient jdbcClient;

  public JdbcNotificationEventRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public List<NotificationEvent> findAll(NotificationEventFilter filter) {
    return jdbcClient
        .sql(FIND_ALL)
        .param("clientId", filter.clientId())
        .param("from", toUtc(filter.from()), Types.TIMESTAMP)
        .param("to", toUtc(filter.to()), Types.TIMESTAMP)
        .param(
            "status",
            filter.deliveryStatus() == null ? null : filter.deliveryStatus().name(),
            Types.VARCHAR)
        .query(ROW_MAPPER)
        .list();
  }

  @Override
  public Optional<NotificationEvent> findById(String clientId, String eventId) {
    return jdbcClient
        .sql(FIND_BY_ID)
        .param("eventId", eventId)
        .param("clientId", clientId)
        .query(ROW_MAPPER)
        .optional();
  }

  private static LocalDateTime toUtc(Instant instant) {
    return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static Instant toInstant(LocalDateTime value) {
    return value == null ? null : value.toInstant(ZoneOffset.UTC);
  }
}
