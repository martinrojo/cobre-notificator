package com.cobre.manager.domain.port.out;

import com.cobre.manager.domain.model.NotificationEvent;
import com.cobre.manager.domain.model.NotificationEventFilter;
import com.cobre.manager.domain.model.PageCursor;
import java.util.List;
import java.util.Optional;

public interface NotificationEventRepository {

  /**
   * Up to {@code limit} events matching the filter, newest first, after the position of {@code
   * after} (null for the first page). Client and filters always come from {@code filter}.
   */
  List<NotificationEvent> findAll(NotificationEventFilter filter, PageCursor after, int limit);

  Optional<NotificationEvent> findById(String clientId, String id);
}
