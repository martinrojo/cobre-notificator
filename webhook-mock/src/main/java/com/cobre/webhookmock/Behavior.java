package com.cobre.webhookmock;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** How the mock answers a webhook: HTTP status after a delay. */
public record Behavior(
    @NotNull @Min(200) @Max(599) Integer status, @NotNull @Min(0) @Max(60000) Long delayMs) {

  static final Behavior OK = new Behavior(200, 0L);
}
