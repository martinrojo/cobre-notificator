package com.cobre.manager.domain.exception;

public final class ReplayPublishException extends RuntimeException {

  private final String eventId;

  private ReplayPublishException(String eventId, Throwable cause) {
    super("Replay request could not be published", cause);
    this.eventId = eventId;
  }

  public static ReplayPublishException of(String eventId, Throwable cause) {
    return new ReplayPublishException(eventId, cause);
  }

  public String eventId() {
    return eventId;
  }
}
