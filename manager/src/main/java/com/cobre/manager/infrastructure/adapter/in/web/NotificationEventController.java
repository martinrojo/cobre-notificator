package com.cobre.manager.infrastructure.adapter.in.web;

import com.cobre.manager.domain.port.in.GetNotificationEventUseCase;
import com.cobre.manager.domain.port.in.ListNotificationEventsUseCase;
import com.cobre.manager.infrastructure.adapter.in.web.dto.NotificationEventResponse;
import com.cobre.manager.infrastructure.adapter.in.web.dto.NotificationEventsRequest;
import com.cobre.manager.infrastructure.adapter.in.web.security.ClientContext;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/notification_events")
public class NotificationEventController {

  private final ListNotificationEventsUseCase listNotificationEvents;
  private final GetNotificationEventUseCase getNotificationEvent;
  private final ClientContext clientContext;

  public NotificationEventController(
      ListNotificationEventsUseCase listNotificationEvents,
      GetNotificationEventUseCase getNotificationEvent,
      ClientContext clientContext) {
    this.listNotificationEvents = listNotificationEvents;
    this.getNotificationEvent = getNotificationEvent;
    this.clientContext = clientContext;
  }

  @GetMapping
  public List<NotificationEventResponse> list(NotificationEventsRequest request) {
    return listNotificationEvents.list(request.toFilter(clientContext.clientId())).stream()
        .map(NotificationEventResponse::from)
        .toList();
  }

  @GetMapping("/{notification_event_id}")
  public NotificationEventResponse get(@PathVariable("notification_event_id") String eventId) {
    return NotificationEventResponse.from(
        getNotificationEvent.get(clientContext.clientId(), eventId));
  }
}
