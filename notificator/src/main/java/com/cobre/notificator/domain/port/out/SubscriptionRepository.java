package com.cobre.notificator.domain.port.out;

import com.cobre.notificator.domain.model.Subscription;
import java.util.Optional;

public interface SubscriptionRepository {

  Optional<Subscription> findActive(String clientId, String eventType);
}
