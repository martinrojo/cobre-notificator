package com.cobre.manager.infrastructure.adapter.in.web.security;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/** Holds the client identified for the current request. Set once by the identification filter. */
@Component
@RequestScope
public class ClientContext {

  private String clientId;

  public String clientId() {
    return clientId;
  }

  void clientId(String clientId) {
    this.clientId = clientId;
  }
}
