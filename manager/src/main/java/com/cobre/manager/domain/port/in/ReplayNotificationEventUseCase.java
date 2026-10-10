package com.cobre.manager.domain.port.in;

public interface ReplayNotificationEventUseCase {

  void replay(String clientId, String eventId);
}
