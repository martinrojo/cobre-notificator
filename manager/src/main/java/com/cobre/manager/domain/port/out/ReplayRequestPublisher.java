package com.cobre.manager.domain.port.out;

import com.cobre.manager.domain.model.ReplayRequested;

public interface ReplayRequestPublisher {

  /**
   * Returns only once the broker acknowledged the write.
   *
   * @throws com.cobre.manager.domain.exception.ReplayPublishException if it could not be confirmed
   */
  void publish(ReplayRequested request);
}
