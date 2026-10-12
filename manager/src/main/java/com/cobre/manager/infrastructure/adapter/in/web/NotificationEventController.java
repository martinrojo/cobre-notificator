package com.cobre.manager.infrastructure.adapter.in.web;

import com.cobre.manager.domain.model.NotificationEventPage;
import com.cobre.manager.domain.port.in.GetNotificationEventUseCase;
import com.cobre.manager.domain.port.in.ListNotificationEventsUseCase;
import com.cobre.manager.domain.port.in.ReplayNotificationEventUseCase;
import com.cobre.manager.infrastructure.adapter.in.web.dto.NotificationEventPageResponse;
import com.cobre.manager.infrastructure.adapter.in.web.dto.NotificationEventResponse;
import com.cobre.manager.infrastructure.adapter.in.web.dto.NotificationEventsRequest;
import com.cobre.manager.infrastructure.adapter.in.web.security.ClientContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/notification_events")
public class NotificationEventController {

  private final ListNotificationEventsUseCase listNotificationEvents;
  private final GetNotificationEventUseCase getNotificationEvent;
  private final ReplayNotificationEventUseCase replayNotificationEvent;
  private final ClientContext clientContext;
  private final CursorCodec cursorCodec;

  public NotificationEventController(
      ListNotificationEventsUseCase listNotificationEvents,
      GetNotificationEventUseCase getNotificationEvent,
      ReplayNotificationEventUseCase replayNotificationEvent,
      ClientContext clientContext,
      CursorCodec cursorCodec) {
    this.listNotificationEvents = listNotificationEvents;
    this.getNotificationEvent = getNotificationEvent;
    this.replayNotificationEvent = replayNotificationEvent;
    this.clientContext = clientContext;
    this.cursorCodec = cursorCodec;
  }

  @GetMapping
  public NotificationEventPageResponse list(NotificationEventsRequest request) {
    NotificationEventPage page =
        listNotificationEvents.list(
            request.toFilter(clientContext.clientId()),
            cursorCodec.decode(request.cursor()),
            request.pageSize());
    return new NotificationEventPageResponse(
        page.events().stream().map(NotificationEventResponse::from).toList(),
        cursorCodec.encode(page.next()));
  }

  @GetMapping("/{notification_event_id}")
  public NotificationEventResponse get(@PathVariable("notification_event_id") String id) {
    return NotificationEventResponse.from(getNotificationEvent.get(clientContext.clientId(), id));
  }

  /** 202 both when the replay is published and when one is already in progress. */
  @PostMapping("/{notification_event_id}/replay")
  public ResponseEntity<Void> replay(@PathVariable("notification_event_id") String id) {
    replayNotificationEvent.replay(clientContext.clientId(), id);
    return ResponseEntity.accepted().build();
  }
}
