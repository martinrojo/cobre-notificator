package com.cobre.notificator.infrastructure.adapter.in.kafka;

/** What a retry or DLT message identifies; the rest of the delivery comes from the row. */
record DeliveryRef(String eventId, String clientId, int generation) {}
