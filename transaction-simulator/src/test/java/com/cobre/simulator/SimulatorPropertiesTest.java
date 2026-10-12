package com.cobre.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SimulatorPropertiesTest {

  @Test
  void defaultRateGivesEachClientA160msInterval() {
    assertThat(new SimulatorProperties("t", 3000, 8).interval()).isEqualTo(Duration.ofMillis(160));
  }

  @Test
  void lowerRateStretchesTheInterval() {
    assertThat(new SimulatorProperties("t", 600, 8).interval()).isEqualTo(Duration.ofMillis(800));
  }

  @Test
  void clientIdsArePaddedAndExact() {
    assertThat(new SimulatorProperties("t", 3000, 8).clientIds())
        .containsExactly(
            "CLIENT001",
            "CLIENT002",
            "CLIENT003",
            "CLIENT004",
            "CLIENT005",
            "CLIENT006",
            "CLIENT007",
            "CLIENT008");
  }

  @Test
  void rejectsNonPositiveValues() {
    assertThatThrownBy(() -> new SimulatorProperties("t", 0, 8))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SimulatorProperties("t", 3000, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
