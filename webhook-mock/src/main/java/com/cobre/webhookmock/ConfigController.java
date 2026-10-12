package com.cobre.webhookmock;

import jakarta.validation.Valid;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Local-only, unauthenticated: compose publishes the port on 127.0.0.1. */
@RestController
@RequestMapping("/config")
public class ConfigController {

  private static final Logger log = LoggerFactory.getLogger(ConfigController.class);

  private final BehaviorStore store;

  public ConfigController(BehaviorStore store) {
    this.store = store;
  }

  @GetMapping
  public Map<String, Object> get() {
    return Map.of("default", store.defaultBehavior(), "clients", store.overrides());
  }

  @PutMapping
  public Behavior setDefault(@Valid @RequestBody Behavior behavior) {
    store.setDefault(behavior);
    log.info(
        "Default behavior changed status={} delay_ms={}", behavior.status(), behavior.delayMs());
    return behavior;
  }

  @PutMapping("/clients/{clientId}")
  public Behavior setOverride(
      @PathVariable String clientId, @Valid @RequestBody Behavior behavior) {
    store.setOverride(clientId, behavior);
    log.info(
        "Client behavior changed client_id={} status={} delay_ms={}",
        clientId,
        behavior.status(),
        behavior.delayMs());
    return behavior;
  }

  @DeleteMapping("/clients/{clientId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removeOverride(@PathVariable String clientId) {
    store.removeOverride(clientId);
    log.info("Client behavior reset client_id={}", clientId);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail invalid(MethodArgumentNotValidException e) {
    String detail =
        e.getFieldErrors().stream()
            .map(error -> snakeCase(error.getField()) + " " + error.getDefaultMessage())
            .collect(Collectors.joining(", "));
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
  }

  /** Names the field as the client wrote it (delay_ms, not delayMs). */
  private static String snakeCase(String field) {
    return field.replaceAll("([A-Z])", "_$1").toLowerCase(Locale.ROOT);
  }
}
