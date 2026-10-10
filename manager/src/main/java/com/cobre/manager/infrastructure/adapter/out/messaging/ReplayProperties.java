package com.cobre.manager.infrastructure.adapter.out.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param publishTimeout how long a request waits for the broker ack before answering 503
 */
@ConfigurationProperties("manager.replay")
public record ReplayProperties(String topic, Duration publishTimeout) {}
