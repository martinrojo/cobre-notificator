package com.cobre.webhookmock;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** In-memory configuration: lost on restart, by design. */
@Component
public class BehaviorStore {

  private volatile Behavior defaultBehavior = Behavior.OK;
  private final Map<String, Behavior> overrides = new ConcurrentHashMap<>();

  public Behavior forClient(String clientId) {
    return overrides.getOrDefault(clientId, defaultBehavior);
  }

  public Behavior defaultBehavior() {
    return defaultBehavior;
  }

  public Map<String, Behavior> overrides() {
    return Map.copyOf(overrides);
  }

  public void setDefault(Behavior behavior) {
    defaultBehavior = behavior;
  }

  public void setOverride(String clientId, Behavior behavior) {
    overrides.put(clientId, behavior);
  }

  public void removeOverride(String clientId) {
    overrides.remove(clientId);
  }
}
