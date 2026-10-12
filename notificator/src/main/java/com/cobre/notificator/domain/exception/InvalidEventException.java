package com.cobre.notificator.domain.exception;

/** A message that can never be processed: goes straight to the DLT, without retries. */
public final class InvalidEventException extends RuntimeException {

  private InvalidEventException(String reason) {
    super("Invalid transaction event: " + reason);
  }

  public static InvalidEventException of(String reason) {
    return new InvalidEventException(reason);
  }
}
