package com.cobre.manager.domain.model;

import java.util.List;

/**
 * @param next cursor for the following page, or null if this is the last one
 */
public record NotificationEventPage(List<NotificationEvent> events, PageCursor next) {}
