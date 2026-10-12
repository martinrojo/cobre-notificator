package com.cobre.notificator.domain.exception;

/** The attempt failed with a retryable error and this process still owns it: schedule the next. */
public final class RetryableDeliveryException extends RuntimeException {

  private RetryableDeliveryException(String eventId, int attempt) {
    super("Delivery attempt " + attempt + " of event " + eventId + " failed, will retry");
  }

  public static RetryableDeliveryException of(String eventId, int attempt) {
    return new RetryableDeliveryException(eventId, attempt);
  }
}
