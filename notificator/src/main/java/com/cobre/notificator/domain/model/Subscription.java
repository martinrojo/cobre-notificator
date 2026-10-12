package com.cobre.notificator.domain.model;

public record Subscription(String clientId, String eventType, String webhookUrl) {}
