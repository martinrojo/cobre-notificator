package com.cobre.notificator.infrastructure.adapter.in.scheduling;

import com.cobre.notificator.domain.port.in.RecoverStaleDeliveriesUseCase;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs on every instance; the conditional writes keep concurrent runs from doubling work. */
@Component
public class StaleDeliveryRecoveryJob {

  private final RecoverStaleDeliveriesUseCase recovery;

  public StaleDeliveryRecoveryJob(RecoverStaleDeliveriesUseCase recovery) {
    this.recovery = recovery;
  }

  @Scheduled(
      fixedDelayString = "${notificator.delivery.recovery.interval}",
      initialDelayString = "${notificator.delivery.recovery.interval}")
  public void run() {
    recovery.recover();
  }
}
