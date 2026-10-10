package com.cobre.manager.domain.model;

import java.time.Instant;

public record NotificationEvent(
    String eventId,
    String clientId,
    String eventType,
    String content,
    DeliveryStatus deliveryStatus,
    Instant createdAt,
    Instant deliveryDate) {}
