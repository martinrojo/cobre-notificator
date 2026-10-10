package com.cobre.manager.domain.port.out;

import com.cobre.manager.domain.model.NotificationEvent;
import com.cobre.manager.domain.model.NotificationEventFilter;
import java.util.List;
import java.util.Optional;

public interface NotificationEventRepository {

  List<NotificationEvent> findAll(NotificationEventFilter filter);

  Optional<NotificationEvent> findById(String clientId, String eventId);
}
