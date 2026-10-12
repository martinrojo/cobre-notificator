package com.cobre.manager.infrastructure.adapter.in.web.dto;

import java.util.List;

/**
 * @param nextCursor token for the following page, null (and serialized) on the last one
 */
public record NotificationEventPageResponse(
    List<NotificationEventResponse> data, String nextCursor) {}
