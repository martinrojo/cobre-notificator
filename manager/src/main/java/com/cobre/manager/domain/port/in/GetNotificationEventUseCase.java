package com.cobre.manager.domain.port.in;

import com.cobre.manager.domain.model.NotificationEvent;

public interface GetNotificationEventUseCase {

  NotificationEvent get(String clientId, String eventId);
}
