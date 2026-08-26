package com.mopl.directmessage.presence;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.mopl.directmessage.websocket.DirectMessageSubscriptionRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DirectMessagePresenceRenewalSchedulerTest {

    @Test
    @DisplayName("DM Presence 갱신 주기마다 활성 세션 TTL 갱신을 요청")
    void renew_success() {
        DirectMessageSubscriptionRegistry registry =
            mock(
                DirectMessageSubscriptionRegistry.class
            );

        DirectMessagePresenceRenewalScheduler scheduler =
            new DirectMessagePresenceRenewalScheduler(
                registry
            );

        scheduler.renew();

        verify(registry).renewPresence();
    }
}
