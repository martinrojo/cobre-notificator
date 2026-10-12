package com.cobre.manager.domain.port.in;

import com.cobre.manager.domain.model.NotificationEventFilter;
import com.cobre.manager.domain.model.NotificationEventPage;
import com.cobre.manager.domain.model.PageCursor;

public interface ListNotificationEventsUseCase {

  /**
   * @param cursor null for the first page
   * @param size null for the default page size
   */
  NotificationEventPage list(NotificationEventFilter filter, PageCursor cursor, Integer size);
}
