package com.cobre.manager.infrastructure.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cobre.manager.infrastructure.adapter.in.web.security.ClientContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PingController.class)
@Import(ClientContext.class)
class PingControllerTest {

  private final MockMvc mockMvc;

  PingControllerTest(@Autowired MockMvc mockMvc) {
    this.mockMvc = mockMvc;
  }

  @Test
  void pingReturnsPong() throws Exception {
    mockMvc
        .perform(get("/ping"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
        .andExpect(content().string("pong"));
  }

  @Test
  void pingReturnsPongWithClientIdHeader() throws Exception {
    mockMvc
        .perform(get("/ping").header("X-Client-Id", "CLIENT001"))
        .andExpect(status().isOk())
        .andExpect(content().string("pong"));
  }
}
