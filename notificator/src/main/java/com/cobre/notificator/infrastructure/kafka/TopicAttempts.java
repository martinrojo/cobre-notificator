package com.cobre.notificator.infrastructure.kafka;

import java.nio.ByteBuffer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

/**
 * The attempt a message executes is derived from the topic it was read from. In the DLT the failing
 * topic is lost (Spring keeps the main topic as original), so there it comes from Spring's attempts
 * header.
 */
public final class TopicAttempts {

  public static final String MAIN = "transactions.events.v1";
  public static final String REPLAY = "notifications.replay-requested.v1";
  public static final String RETRY_PREFIX = "notificator.retry.v1-";
  public static final String DLT = "notificator.dlt.v1";

  /**
   * Spring's attempts header (4-byte int): it holds the attempt that a retry or DLT message comes
   * after, plus one. Only the DLT needs it; the recovery job sets it on republished messages.
   */
  public static final String ATTEMPTS_HEADER = "retry_topic-attempts";

  private TopicAttempts() {}

  /**
   * transactions.events.v1 and notifications.replay-requested.v1 -> 1 (of their generation),
   * notificator.retry.v1-i -> i + 2.
   */
  public static int attemptFor(String topic) {
    if (MAIN.equals(topic) || REPLAY.equals(topic)) {
      return 1;
    }
    if (topic.startsWith(RETRY_PREFIX)) {
      return Integer.parseInt(topic.substring(RETRY_PREFIX.length())) + 2;
    }
    throw new IllegalArgumentException("Not a delivery topic: " + topic);
  }

  /**
   * Attempt that failed and sent the message to the DLT. Without {@link #ATTEMPTS_HEADER} the
   * message failed on the main topic, at attempt 1.
   */
  public static int failedAttemptInDlt(Headers headers) {
    Header attempts = headers.lastHeader(ATTEMPTS_HEADER);
    return attempts == null ? 1 : failedAttemptInDlt(attempts.value());
  }

  /** Attempt that failed and sent the message to the DLT, from {@link #ATTEMPTS_HEADER}. */
  public static int failedAttemptInDlt(byte[] attemptsHeader) {
    int next =
        attemptsHeader.length == Integer.BYTES
            ? ByteBuffer.wrap(attemptsHeader).getInt()
            : Byte.toUnsignedInt(attemptsHeader[0]);
    return next - 1;
  }

  public static byte[] attemptsHeader(int lastAttempt) {
    return ByteBuffer.allocate(Integer.BYTES).putInt(lastAttempt + 1).array();
  }

  /**
   * Retry topic that runs the attempt after {@code lastAttempt}: k -> notificator.retry.v1-(k-1).
   */
  public static String retryTopicAfter(int lastAttempt) {
    return RETRY_PREFIX + (lastAttempt - 1);
  }
}
