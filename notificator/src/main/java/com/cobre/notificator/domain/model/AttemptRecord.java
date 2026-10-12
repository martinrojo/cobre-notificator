package com.cobre.notificator.domain.model;

import java.time.Instant;

/**
 * Diagnostic data of one attempt.
 *
 * @param deliveryUrl null when no webhook was resolved
 * @param httpStatus null when there was no response
 * @param error short description, never the event content nor the response body
 */
public record AttemptRecord(
    Instant attemptedAt, String deliveryUrl, Integer httpStatus, String error) {}
