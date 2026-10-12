package com.cobre.notificator.domain.port.in;

public interface FailExhaustedEventUseCase {

  /**
   * Marks FAILED only if {@code generation} and {@code attempt} are still current and non-final.
   */
  void failExhausted(String eventId, String clientId, int generation, int attempt);
}
