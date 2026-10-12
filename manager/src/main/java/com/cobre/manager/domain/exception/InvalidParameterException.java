package com.cobre.manager.domain.exception;

public final class InvalidParameterException extends RuntimeException {

  private InvalidParameterException(String message) {
    super(message);
  }

  public static InvalidParameterException invalidDate(String param) {
    return new InvalidParameterException(
        "'%s' must be an ISO-8601 date-time with offset, e.g. 2024-03-15T10:00:00Z"
            .formatted(param));
  }

  public static InvalidParameterException invalidRange() {
    return new InvalidParameterException("'from' must be before or equal to 'to'");
  }

  public static InvalidParameterException invalidSizeFormat() {
    return new InvalidParameterException("'size' must be an integer");
  }

  public static InvalidParameterException invalidSize(int max) {
    return new InvalidParameterException("'size' must be between 1 and %d".formatted(max));
  }

  /** Also for a cursor of another client: the response does not reveal that it is valid. */
  public static InvalidParameterException invalidCursor() {
    return new InvalidParameterException("'cursor' is invalid");
  }

  public static InvalidParameterException cursorFilterMismatch() {
    return new InvalidParameterException("'cursor' does not match the current filters");
  }
}
