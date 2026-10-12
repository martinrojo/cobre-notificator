package com.cobre.notificator.domain.port.out;

import com.cobre.notificator.domain.model.TransactionEvent;
import com.cobre.notificator.domain.model.WebhookResult;

public interface WebhookClient {

  /**
   * One POST of {@code event} as notification {@code notificationEventId}, no redirects; never
   * throws for an HTTP status or a transport error.
   */
  WebhookResult post(String url, String notificationEventId, TransactionEvent event, int attempt);
}
