package com.cobre.simulator;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** Value of transactions.events.v1, same contract the notificator consumes. */
public record SimulatedEvent(
    String eventId, String clientId, String eventType, String content, Instant createdAt) {

  /** Types from notification_events.json. */
  static final List<String> EVENT_TYPES =
      List.of(
          "credit_card_payment",
          "credit_cashback",
          "credit_deposit",
          "credit_refund",
          "credit_transfer",
          "debit_automatic_payment",
          "debit_card_withdrawal",
          "debit_purchase",
          "debit_subscription",
          "debit_transfer");

  static SimulatedEvent random(String clientId, RandomGenerator random) {
    String type = EVENT_TYPES.get(random.nextInt(EVENT_TYPES.size()));
    double amount = random.nextDouble(1, 5000);
    String label = Character.toUpperCase(type.charAt(0)) + type.substring(1).replace('_', ' ');
    return new SimulatedEvent(
        "EVT-" + UUID.randomUUID(),
        clientId,
        type,
        String.format(Locale.ROOT, "%s of $%.2f", label, amount),
        Instant.now());
  }
}
