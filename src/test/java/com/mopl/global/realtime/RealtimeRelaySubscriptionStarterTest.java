package com.mopl.global.realtime;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class RealtimeRelaySubscriptionStarterTest {

    private final RealtimeRelayListenerContainer container =
        mock(RealtimeRelayListenerContainer.class);
    private final RealtimeRelaySubscriptionStarter starter =
        new RealtimeRelaySubscriptionStarter(container);

    @Test
    @DisplayName("이미 구독 중이면 컨테이너를 중지하거나 다시 시작하지 않는다")
    void skipsRestartWhenAlreadySubscribed() {
        when(container.isSubscribed()).thenReturn(true);

        starter.ensureSubscribed();

        verify(container, never()).stop();
        verify(container, never()).start();
    }

    @Test
    @DisplayName("미구독 상태에서만 stop 다음 start 순서로 다시 구독한다")
    void restartsUnsubscribedContainerInOrder() {
        when(container.isSubscribed()).thenReturn(false, true);

        starter.ensureSubscribed();
        starter.ensureSubscribed();

        InOrder lifecycle = inOrder(container);
        lifecycle.verify(container).isSubscribed();
        lifecycle.verify(container).stop();
        lifecycle.verify(container).start();
        lifecycle.verify(container).isSubscribed();
        lifecycle.verifyNoMoreInteractions();
    }
}
