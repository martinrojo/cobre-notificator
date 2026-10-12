package com.cobre.notificator.domain.port.in;

import com.cobre.notificator.domain.model.ReplayRequest;
import com.cobre.notificator.domain.model.TransactionEvent;

public interface DeliverEventUseCase {

  /** Attempt 1 of generation 0, from the main topic. A duplicate is discarded. */
  void first(TransactionEvent event);

  /**
   * Attempt 1 of the next generation of a FAILED event. A duplicate or stale request is discarded.
   */
  void replay(ReplayRequest request);

  /** Attempt {@code attempt} (2..max) of {@code generation}, from a retry topic. */
  void retry(String eventId, String clientId, int generation, int attempt);
}
