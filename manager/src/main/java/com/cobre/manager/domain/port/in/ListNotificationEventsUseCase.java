package com.cobre.manager.domain.port.in;

import com.cobre.manager.domain.model.NotificationEvent;
import com.cobre.manager.domain.model.NotificationEventFilter;
import java.util.List;

public interface ListNotificationEventsUseCase {

  List<NotificationEvent> list(NotificationEventFilter filter);
}
