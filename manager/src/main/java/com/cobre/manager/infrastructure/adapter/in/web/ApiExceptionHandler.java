package com.cobre.manager.infrastructure.adapter.in.web;

import com.cobre.manager.domain.exception.InvalidParameterException;
import com.cobre.manager.domain.exception.NotificationEventNotFoundException;
import com.cobre.manager.domain.exception.ReplayNotAllowedException;
import com.cobre.manager.domain.exception.ReplayPublishException;
import com.cobre.manager.domain.model.DeliveryStatus;
import com.cobre.manager.infrastructure.adapter.in.web.security.UnauthenticatedClientException;
import java.time.Duration;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

  private static final String INVALID_DELIVERY_STATUS =
      "'delivery_status' must be one of " + Arrays.toString(DeliveryStatus.values());

  private final Duration replayRetryAfter;

  public ApiExceptionHandler(@Value("${manager.replay.retry-after}") Duration replayRetryAfter) {
    this.replayRetryAfter = replayRetryAfter;
  }

  @ExceptionHandler(UnauthenticatedClientException.class)
  ProblemDetail handleUnauthenticated(UnauthenticatedClientException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
  }

  @ExceptionHandler(InvalidParameterException.class)
  ProblemDetail handleInvalidParameter(InvalidParameterException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(NotificationEventNotFoundException.class)
  ProblemDetail handleNotFound(NotificationEventNotFoundException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(ReplayNotAllowedException.class)
  ProblemDetail handleReplayNotAllowed(ReplayNotAllowedException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
  }

  // The technical cause is logged by the service, never returned to the client.
  @ExceptionHandler(ReplayPublishException.class)
  ResponseEntity<ProblemDetail> handleReplayUnavailable(ReplayPublishException e) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header(HttpHeaders.RETRY_AFTER, String.valueOf(replayRetryAfter.toSeconds()))
        .body(
            ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "Replay could not be requested, try again later"));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail handleBinding(MethodArgumentNotValidException e) {
    var fieldError = e.getBindingResult().getFieldError();
    String detail =
        fieldError != null && "delivery_status".equals(fieldError.getField())
            ? INVALID_DELIVERY_STATUS
            : "Invalid request parameters";
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
  }
}
