package com.cobre.manager.application;

import com.cobre.manager.domain.exception.InvalidParameterException;
import com.cobre.manager.domain.exception.NotificationEventNotFoundException;
import com.cobre.manager.domain.model.NotificationEvent;
import com.cobre.manager.domain.model.NotificationEventFilter;
import com.cobre.manager.domain.model.NotificationEventPage;
import com.cobre.manager.domain.model.PageCursor;
import com.cobre.manager.domain.port.in.GetNotificationEventUseCase;
import com.cobre.manager.domain.port.in.ListNotificationEventsUseCase;
import com.cobre.manager.domain.port.out.NotificationEventRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class NotificationEventQueryService
    implements ListNotificationEventsUseCase, GetNotificationEventUseCase {

  private static final Logger log = LoggerFactory.getLogger(NotificationEventQueryService.class);

  private final NotificationEventRepository repository;
  private final int defaultPageSize;
  private final int maxPageSize;

  public NotificationEventQueryService(
      NotificationEventRepository repository,
      @Value("${manager.query.default-page-size}") int defaultPageSize,
      @Value("${manager.query.max-page-size}") int maxPageSize) {
    this.repository = repository;
    this.defaultPageSize = defaultPageSize;
    this.maxPageSize = maxPageSize;
  }

  @Override
  public NotificationEventPage list(
      NotificationEventFilter filter, PageCursor cursor, Integer size) {
    int pageSize = size == null ? defaultPageSize : size;
    if (pageSize < 1 || pageSize > maxPageSize) {
      throw InvalidParameterException.invalidSize(maxPageSize);
    }
    if (cursor != null) {
      // Client first, with the same error as a malformed cursor: never confirm another client's.
      if (!cursor.filter().clientId().equals(filter.clientId())) {
        throw InvalidParameterException.invalidCursor();
      }
      // Records compare parsed values: same instant in another offset, any case of the status.
      if (!cursor.filter().equals(filter)) {
        throw InvalidParameterException.cursorFilterMismatch();
      }
    }

    // One extra row tells whether there is a next page, without a COUNT.
    List<NotificationEvent> rows = repository.findAll(filter, cursor, pageSize + 1);
    NotificationEventPage page =
        rows.size() <= pageSize
            ? new NotificationEventPage(rows, null)
            : page(rows.subList(0, pageSize), filter);
    log.atDebug()
        .addKeyValue("from", filter.from())
        .addKeyValue("to", filter.to())
        .addKeyValue("delivery_status", filter.deliveryStatus())
        .addKeyValue("size", pageSize)
        .addKeyValue("has_cursor", cursor != null)
        .addKeyValue("count", page.events().size())
        .log("Listed notification events");
    return page;
  }

  private static NotificationEventPage page(
      List<NotificationEvent> events, NotificationEventFilter filter) {
    NotificationEvent last = events.getLast();
    return new NotificationEventPage(
        List.copyOf(events), new PageCursor(last.createdAt(), last.id(), filter));
  }

  @Override
  public NotificationEvent get(String clientId, String id) {
    NotificationEvent event =
        repository
            .findById(clientId, id)
            .orElseThrow(() -> NotificationEventNotFoundException.of(id));
    log.atDebug()
        .addKeyValue("notification_event_id", id)
        .addKeyValue("event_id", event.eventId())
        .log("Found notification event");
    return event;
  }
}
