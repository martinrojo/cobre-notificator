package com.cobre.manager.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cobre.manager.domain.model.DeliveryStatus;
import org.junit.jupiter.api.Test;

class DeliveryStatusConverterTest {

  private final DeliveryStatusConverter converter = new DeliveryStatusConverter();

  @Test
  void convertsIgnoringCaseAndSpaces() {
    assertThat(converter.convert("delivered")).isEqualTo(DeliveryStatus.DELIVERED);
    assertThat(converter.convert(" Not_Subscribed ")).isEqualTo(DeliveryStatus.NOT_SUBSCRIBED);
  }

  @Test
  void blankIsNoFilter() {
    assertThat(converter.convert("  ")).isNull();
  }

  @Test
  void rejectsUnknownValue() {
    assertThatThrownBy(() -> converter.convert("COMPLETED"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
