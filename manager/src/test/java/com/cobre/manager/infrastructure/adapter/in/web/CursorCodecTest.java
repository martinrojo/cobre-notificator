package com.cobre.manager.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.manager.domain.exception.InvalidParameterException;
import com.cobre.manager.domain.model.DeliveryStatus;
import com.cobre.manager.domain.model.NotificationEventFilter;
import com.cobre.manager.domain.model.PageCursor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class CursorCodecTest {

  private final CursorCodec codec = new CursorCodec(JsonMapper.builder().build());

  @Test
  void roundTripKeepsPositionClientAndFilters() {
    var cursor =
        new PageCursor(
            Instant.parse("2024-03-15T09:30:22.123Z"),
            "01a1311c-4907-7a3c-9814-0adb01ff9e75",
            new NotificationEventFilter(
                "CLIENT001", Instant.parse("2024-03-15T00:00:00Z"), null, DeliveryStatus.FAILED));

    String token = codec.encode(cursor);

    assertThat(token).doesNotContain("=", "+", "/");
    assertThat(codec.decode(token)).isEqualTo(cursor);
  }

  @Test
  void noCursor() {
    assertThat(codec.encode(null)).isNull();
    assertThat(codec.decode(null)).isNull();
    assertThat(codec.decode(" ")).isNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not-a-cursor",
        "{}",
        "[]",
        "{\"createdAt\":\"2024-03-15T09:30:22Z\"}",
        "{\"createdAt\":\"2024-03-15T09:30:22Z\",\"id\":\"EVT001\",\"filter\":{\"clientId\":\"C\"}}",
        "{\"createdAt\":\"yesterday\",\"id\":\"01a1311c-4907-7a3c-9814-0adb01ff9e75\","
            + "\"filter\":{\"clientId\":\"C\"}}",
        "{\"createdAt\":\"2024-03-15T09:30:22Z\",\"id\":\"01a1311c-4907-7a3c-9814-0adb01ff9e75\"}"
      })
  void rejectsTokensThatAreNotACursor(String raw) {
    // "not-a-cursor" is not base64url; the rest are valid base64url of invalid JSON cursors.
    String token =
        raw.equals("not-a-cursor")
            ? raw
            : Base64.getUrlEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> codec.decode(token))
        .isInstanceOf(InvalidParameterException.class)
        .hasMessage("'cursor' is invalid");
  }
}
