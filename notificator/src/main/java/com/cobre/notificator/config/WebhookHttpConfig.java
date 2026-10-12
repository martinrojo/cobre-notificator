package com.cobre.notificator.config;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class WebhookHttpConfig {

  /**
   * Redirects are never followed: a 3xx is a final answer, and following it could send the event to
   * a host the client never subscribed (SSRF).
   */
  @Bean
  public RestClient webhookRestClient(DeliveryProperties properties) {
    DeliveryProperties.Webhook webhook = properties.webhook();
    HttpClient httpClient =
        HttpClient.newBuilder()
            .connectTimeout(webhook.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(webhook.readTimeout());
    return RestClient.builder().requestFactory(requestFactory).build();
  }
}
