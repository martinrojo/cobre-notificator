package com.cobre.manager.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Position after the last event of a page (keyset on created_at DESC, id DESC) plus the filter the
 * page was listed with, client included: a cursor only continues the same listing.
 */
public record PageCursor(Instant createdAt, String id, NotificationEventFilter filter) {

  public PageCursor {
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(filter, "filter");
    // Canonical UUID only: the id goes straight into a comparison against the UUID column.
    if (!UUID.fromString(id).toString().equals(id)) {
      throw new IllegalArgumentException("id must be a canonical UUID");
    }
  }
}
