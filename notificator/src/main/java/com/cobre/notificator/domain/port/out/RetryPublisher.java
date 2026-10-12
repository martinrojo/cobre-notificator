package com.cobre.notificator.domain.port.out;

import com.cobre.notificator.domain.model.NotificationEvent;

public interface RetryPublisher {

  /**
   * Publishes to the retry topic of attempt {@code lastAttempt + 1}, in the format of the flow that
   * opened the event's generation; blocks until the ack.
   */
  void publish(NotificationEvent event, int lastAttempt);
}
