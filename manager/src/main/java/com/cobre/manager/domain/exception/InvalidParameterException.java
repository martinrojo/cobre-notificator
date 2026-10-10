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
}
