package com.cobre.manager.infrastructure.adapter.in.web;

import com.cobre.manager.domain.exception.InvalidParameterException;
import com.cobre.manager.domain.exception.NotificationEventNotFoundException;
import com.cobre.manager.domain.model.DeliveryStatus;
import com.cobre.manager.infrastructure.adapter.in.web.security.UnauthenticatedClientException;
import java.util.Arrays;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

  private static final String INVALID_DELIVERY_STATUS =
      "'delivery_status' must be one of " + Arrays.toString(DeliveryStatus.values());

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
