package com.cobre.manager.infrastructure.adapter.in.web.security;

public final class UnauthenticatedClientException extends RuntimeException {

  private UnauthenticatedClientException(String message) {
    super(message);
  }

  public static UnauthenticatedClientException missingClientId() {
    return new UnauthenticatedClientException("Client identification is missing");
  }
}
