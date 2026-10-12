package com.cobre.webhookmock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(WebhookController.class)
@Import(BehaviorStore.class)
// The store is a singleton with mutable state: fresh context per test.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestPropertySource(properties = "logging.level.com.cobre.webhookmock=DEBUG")
@ExtendWith(OutputCaptureExtension.class)
class WebhookControllerTest {

  private static final String SECRET_CONTENT = "Credit deposit of $4242.42";

  @Autowired private MockMvc mvc;
  @Autowired private BehaviorStore store;

  private ResultActions deliver(String pathClientId, String bodyClientId) throws Exception {
    return mvc.perform(
        post("/webhooks/" + pathClientId)
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Delivery-Attempt", "1")
            .content(
                """
                {"event_id":"EVT400","client_id":"%s","event_type":"credit_deposit",\
                "content":"%s","created_at":"2026-10-11T10:00:00Z"}"""
                    .formatted(bodyClientId, SECRET_CONTENT)));
  }

  @Test
  void answersDefaultWithEmptyBody(CapturedOutput output) throws Exception {
    deliver("CLIENT001", "CLIENT001").andExpect(status().isOk()).andExpect(content().string(""));

    assertThat(output).contains("EVT400").doesNotContain("ERROR").doesNotContain(SECRET_CONTENT);
  }

  @Test
  void overrideOfOneClientDoesNotAffectAnother() throws Exception {
    store.setOverride("CLIENT004", new Behavior(503, 0L));

    deliver("CLIENT004", "CLIENT004").andExpect(status().isServiceUnavailable());
    deliver("CLIENT005", "CLIENT005").andExpect(status().isOk());
  }

  @Test
  void logsCrossClientDeliveryWithoutContent(CapturedOutput output) throws Exception {
    deliver("CLIENT001", "CLIENT002").andExpect(status().isOk());

    assertThat(output)
        .contains("ERROR")
        .contains("Cross-client delivery")
        .contains("CLIENT001")
        .contains("CLIENT002")
        .contains("EVT400")
        .doesNotContain(SECRET_CONTENT);
  }
}
