package com.cobre.notificator.it;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Integration tests against the compose MariaDB, enabled only when IT_DB_URL is set (see README).
 * Code under test uses the notificator user, so missing grants fail the test; fixtures and cleanup
 * use root, since the notificator cannot write subscription nor delete events.
 */
public final class MariaDb {

  public static final String ENABLED_VAR = "IT_DB_URL";

  private MariaDb() {}

  public static JdbcClient notificator() {
    return client(System.getenv("NOTIFICATOR_DB_USER"), System.getenv("NOTIFICATOR_DB_PASSWORD"));
  }

  public static JdbcClient root() {
    return client("root", System.getenv("MARIADB_ROOT_PASSWORD"));
  }

  /** Unique per test run, so tests never collide with the seed or each other. */
  public static String newId() {
    return "IT-" + UUID.randomUUID().toString().substring(0, 8);
  }

  /** Every client's row of {@code eventId}: test ids are unique per run (see {@link #newId}). */
  public static void deleteEvent(String eventId) {
    root().sql("DELETE FROM notification_event WHERE event_id = ?").param(eventId).update();
  }

  public static void insertSubscription(
      String clientId, String eventType, String url, boolean active) {
    root()
        .sql(
            "INSERT INTO subscription (client_id, event_type, webhook_url, active)"
                + " VALUES (?, ?, ?, ?)")
        .params(clientId, eventType, url, active)
        .update();
  }

  public static void deleteSubscriptions(String clientId) {
    root().sql("DELETE FROM subscription WHERE client_id = ?").param(clientId).update();
  }

  /** Moves updated_at to the past, as if the event had not changed since then. */
  public static void ageEvent(String eventId, String clientId, long seconds) {
    root()
        .sql(
            "UPDATE notification_event SET updated_at = NOW(3) - INTERVAL ? SECOND"
                + " WHERE event_id = ? AND client_id = ?")
        .params(seconds, eventId, clientId)
        .update();
  }

  public static Instant now() {
    return Instant.parse("2026-10-11T10:00:00Z");
  }

  private static JdbcClient client(String user, String password) {
    return JdbcClient.create(
        new DriverManagerDataSource(System.getenv(ENABLED_VAR), user, password));
  }
}
