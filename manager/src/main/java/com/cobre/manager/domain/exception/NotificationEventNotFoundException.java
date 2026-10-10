package com.cobre.manager.domain.exception;

/** Not found, or owned by another client: both cases are indistinguishable to the caller. */
public final class NotificationEventNotFoundException extends RuntimeException {

  private final String eventId;

  private NotificationEventNotFoundException(String eventId) {
    super("Notification event not found");
    this.eventId = eventId;
  }

  public static NotificationEventNotFoundException of(String eventId) {
    return new NotificationEventNotFoundException(eventId);
  }

  public String eventId() {
    return eventId;
  }
}
