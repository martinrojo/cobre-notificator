package com.cobre.manager.domain.model;

import java.time.Instant;

public record ReplayRequested(String eventId, String clientId, Instant requestedAt) {}
