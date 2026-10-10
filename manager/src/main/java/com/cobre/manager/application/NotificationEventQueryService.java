package com.cobre.manager.application;

import com.cobre.manager.domain.exception.NotificationEventNotFoundException;
import com.cobre.manager.domain.model.NotificationEvent;
import com.cobre.manager.domain.model.NotificationEventFilter;
import com.cobre.manager.domain.port.in.GetNotificationEventUseCase;
import com.cobre.manager.domain.port.in.ListNotificationEventsUseCase;
import com.cobre.manager.domain.port.out.NotificationEventRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class NotificationEventQueryService
    implements ListNotificationEventsUseCase, GetNotificationEventUseCase {

  private static final Logger log = LoggerFactory.getLogger(NotificationEventQueryService.class);

  private final NotificationEventRepository repository;

  public NotificationEventQueryService(NotificationEventRepository repository) {
    this.repository = repository;
  }

  @Override
  public List<NotificationEvent> list(NotificationEventFilter filter) {
    List<NotificationEvent> events = repository.findAll(filter);
    log.debug(
        "Listed notification events client_id={} from={} to={} delivery_status={} count={}",
        filter.clientId(),
        filter.from(),
        filter.to(),
        filter.deliveryStatus(),
        events.size());
    return events;
  }

  @Override
  public NotificationEvent get(String clientId, String eventId) {
    NotificationEvent event =
        repository
            .findById(clientId, eventId)
            .orElseThrow(() -> NotificationEventNotFoundException.of(eventId));
    log.debug("Found notification event event_id={}", eventId);
    return event;
  }
}
