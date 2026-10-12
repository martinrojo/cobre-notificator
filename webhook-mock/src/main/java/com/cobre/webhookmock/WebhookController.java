package com.cobre.webhookmock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Answers as configured for the client in the path. Never maps nor logs the event content. */
@RestController
public class WebhookController {

  private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

  /** Only the fields the mock needs; content is ignored on purpose. */
  record WebhookCall(String eventId, String clientId) {}

  private final BehaviorStore store;

  public WebhookController(BehaviorStore store) {
    this.store = store;
  }

  @PostMapping("/webhooks/{clientId}")
  public ResponseEntity<Void> receive(
      @PathVariable String clientId,
      @RequestBody WebhookCall call,
      @RequestHeader(name = "X-Delivery-Attempt", required = false) String attempt)
      throws InterruptedException {
    if (!clientId.equals(call.clientId())) {
      log.error(
          "Cross-client delivery path_client_id={} body_client_id={} event_id={}",
          clientId,
          call.clientId(),
          call.eventId());
    }
    Behavior behavior = store.forClient(clientId);
    Thread.sleep(behavior.delayMs());
    log.debug(
        "Webhook received client_id={} event_id={} attempt={} status={} delay_ms={}",
        clientId,
        call.eventId(),
        attempt,
        behavior.status(),
        behavior.delayMs());
    return ResponseEntity.status(behavior.status()).build();
  }
}
