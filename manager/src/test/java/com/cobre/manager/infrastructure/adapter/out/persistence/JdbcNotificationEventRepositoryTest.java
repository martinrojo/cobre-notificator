package com.cobre.manager.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcNotificationEventRepositoryTest {

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "",
        "EVT001",
        "1-1-1-1-1",
        "01a1311c-4907-7a3c-9814-0adb01ff9e7", // 35 characters
        "01a1311c-4907-7a3c-9814-0adb01ff9e7z"
      })
  void nonUuidIsNotAnId(String id) {
    assertThat(JdbcNotificationEventRepository.canonicalId(id)).isEmpty();
  }

  @Test
  void uppercaseUuidIsNormalized() {
    assertThat(JdbcNotificationEventRepository.canonicalId("01A1311C-4907-7A3C-9814-0ADB01FF9E75"))
        .contains("01a1311c-4907-7a3c-9814-0adb01ff9e75");
  }

  @Test
  void nonUuidIsNotFoundWithoutQuerying() {
    JdbcClient jdbcClient = mock(JdbcClient.class);

    assertThat(new JdbcNotificationEventRepository(jdbcClient).findById("CLIENT001", "EVT001"))
        .isEmpty();
    verifyNoInteractions(jdbcClient);
  }
}
