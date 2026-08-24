package com.mopl.directmessage.presence;

import com.mopl.directmessage.websocket.DirectMessageSubscriptionRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DirectMessagePresenceRenewalScheduler {

    private final DirectMessageSubscriptionRegistry subscriptionRegistry;

    @Scheduled(fixedDelayString = "${mopl.direct-message.presence.renew-interval}")
    public void renew() {
        subscriptionRegistry.renewPresence();
    }
}
