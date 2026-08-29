package com.mopl.global.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RealtimeRelayListenerContainerTest {

    @Test
    @DisplayName("실행 중이 아니면 listening을 조회하지 않고 미구독으로 판단한다")
    void stoppedContainerIsNotSubscribed() {
        RealtimeRelayListenerContainer container = spy(new RealtimeRelayListenerContainer());
        doReturn(false).when(container).isRunning();

        assertThat(container.isSubscribed()).isFalse();

        verify(container, never()).isListening();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("실행 중인 컨테이너는 실제 listening 상태로 구독 여부를 판단한다")
    void runningContainerRequiresActiveSubscription(boolean listening) {
        RealtimeRelayListenerContainer container = spy(new RealtimeRelayListenerContainer());
        doReturn(true).when(container).isRunning();
        doReturn(listening).when(container).isListening();

        assertThat(container.isSubscribed()).isEqualTo(listening);

        verify(container).isListening();
    }
}
