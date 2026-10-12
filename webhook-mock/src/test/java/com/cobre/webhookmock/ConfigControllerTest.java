package com.cobre.webhookmock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ConfigController.class)
@Import(BehaviorStore.class)
// The store is a singleton with mutable state: fresh context per test.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ConfigControllerTest {

  @Autowired private MockMvc mvc;
  @Autowired private BehaviorStore store;

  @Test
  void setsDefault() throws Exception {
    mvc.perform(
            put("/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":500,\"delay_ms\":2000}"))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"status\":500,\"delay_ms\":2000}"));

    assertThat(store.forClient("CLIENT001")).isEqualTo(new Behavior(500, 2000L));
  }

  @Test
  void returnsDefaultAndOverrides() throws Exception {
    store.setOverride("CLIENT004", new Behavior(503, 0L));

    mvc.perform(get("/config"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .json(
                    "{\"default\":{\"status\":200,\"delay_ms\":0},"
                        + "\"clients\":{\"CLIENT004\":{\"status\":503,\"delay_ms\":0}}}",
                    true));
  }

  @Test
  void deleteResetsClientEvenIfAbsent() throws Exception {
    store.setOverride("CLIENT004", new Behavior(503, 0L));

    mvc.perform(delete("/config/clients/CLIENT004")).andExpect(status().isNoContent());
    mvc.perform(delete("/config/clients/CLIENT009")).andExpect(status().isNoContent());

    assertThat(store.forClient("CLIENT004").status()).isEqualTo(200);
  }

  @Test
  void rejectsInvalidStatusWithoutChangingConfig() throws Exception {
    store.setOverride("CLIENT001", new Behavior(503, 0L));

    mvc.perform(
            put("/config/clients/CLIENT001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":99,\"delay_ms\":0}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail", Matchers.containsString("status")));

    assertThat(store.forClient("CLIENT001").status()).isEqualTo(503);
  }

  @Test
  void rejectsOutOfRangeOrMissingDelay() throws Exception {
    mvc.perform(
            put("/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":200,\"delay_ms\":120000}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail", Matchers.containsString("delay_ms")));
    mvc.perform(put("/config").contentType(MediaType.APPLICATION_JSON).content("{\"status\":200}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail", Matchers.containsString("delay_ms")));

    assertThat(store.defaultBehavior()).isEqualTo(Behavior.OK);
  }
}
