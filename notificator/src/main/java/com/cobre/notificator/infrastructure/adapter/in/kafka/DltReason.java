package com.cobre.notificator.infrastructure.adapter.in.kafka;

import java.util.Locale;

/** Why a message reached the DLT; the value of the {@code reason} metric tag. */
enum DltReason {
  EXHAUSTED,
  INVALID,
  UNEXPECTED;

  String tag() {
    return name().toLowerCase(Locale.ROOT);
  }
}
