package com.cobre.notificator.infrastructure.adapter.out.persistence;

import com.cobre.notificator.domain.model.Subscription;
import com.cobre.notificator.domain.port.out.SubscriptionRepository;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSubscriptionRepository implements SubscriptionRepository {

  // client_id is always part of the lookup: an event never resolves another client's webhook.
  private static final String FIND_ACTIVE =
      """
      SELECT client_id, event_type, webhook_url
      FROM subscription
      WHERE client_id = :clientId AND event_type = :eventType AND active = TRUE
      """;

  private final JdbcClient jdbcClient;

  public JdbcSubscriptionRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public Optional<Subscription> findActive(String clientId, String eventType) {
    return jdbcClient
        .sql(FIND_ACTIVE)
        .param("clientId", clientId)
        .param("eventType", eventType)
        .query(
            (rs, rowNum) ->
                new Subscription(
                    rs.getString("client_id"),
                    rs.getString("event_type"),
                    rs.getString("webhook_url")))
        .optional();
  }
}
