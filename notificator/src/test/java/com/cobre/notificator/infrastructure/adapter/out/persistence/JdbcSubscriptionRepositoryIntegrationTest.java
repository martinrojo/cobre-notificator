package com.cobre.notificator.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.cobre.notificator.domain.model.Subscription;
import com.cobre.notificator.it.MariaDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = MariaDb.ENABLED_VAR, matches = ".+")
class JdbcSubscriptionRepositoryIntegrationTest {

  private final String client = MariaDb.newId();
  private final String otherClient = MariaDb.newId();
  private final JdbcSubscriptionRepository repository =
      new JdbcSubscriptionRepository(MariaDb.notificator());

  @AfterEach
  void tearDown() {
    MariaDb.deleteSubscriptions(client);
    MariaDb.deleteSubscriptions(otherClient);
  }

  @Test
  void findsActiveSubscription() {
    MariaDb.insertSubscription(client, "credit_deposit", "https://a.example/hook", true);

    assertThat(repository.findActive(client, "credit_deposit"))
        .contains(new Subscription(client, "credit_deposit", "https://a.example/hook"));
  }

  @Test
  void inactiveSubscriptionIsNotFound() {
    MariaDb.insertSubscription(client, "credit_deposit", "https://a.example/hook", false);

    assertThat(repository.findActive(client, "credit_deposit")).isEmpty();
  }

  @Test
  void neverReturnsAnotherClientsSubscription() {
    MariaDb.insertSubscription(otherClient, "credit_deposit", "https://b.example/hook", true);

    assertThat(repository.findActive(client, "credit_deposit")).isEmpty();
  }
}
