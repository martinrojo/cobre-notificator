package com.cobre.manager.infrastructure.adapter.in.web;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PingController {

  @GetMapping(value = "/ping", produces = MediaType.TEXT_PLAIN_VALUE)
  public String ping() {
    return "pong";
  }
}
