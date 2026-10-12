package com.cobre.webhookmock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BehaviorStoreTest {

  private final BehaviorStore store = new BehaviorStore();

  @Test
  void startsWith200AndNoDelay() {
    assertThat(store.forClient("CLIENT001")).isEqualTo(new Behavior(200, 0L));
  }

  @Test
  void overrideOnlyAffectsItsClient() {
    store.setOverride("CLIENT004", new Behavior(503, 0L));

    assertThat(store.forClient("CLIENT004").status()).isEqualTo(503);
    assertThat(store.forClient("CLIENT005").status()).isEqualTo(200);
  }

  @Test
  void removingOverrideFallsBackToDefault() {
    store.setDefault(new Behavior(500, 10L));
    store.setOverride("CLIENT004", new Behavior(503, 0L));

    store.removeOverride("CLIENT004");

    assertThat(store.forClient("CLIENT004")).isEqualTo(new Behavior(500, 10L));
  }
}
