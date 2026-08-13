package com.mopl.support.websocket;

import static com.mopl.support.websocket.StompTestCleanup.closeAll;
import static com.mopl.support.websocket.StompTestCleanup.disconnectQuietly;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.messaging.WebSocketStompClient;

class StompTestCleanupTest {

    @Test
    @DisplayName("disconnectQuietly()는 세션이 null이면 아무 일도 하지 않는다")
    void disconnectQuietly_doesNothing_whenSessionIsNull() {
        assertThatCode(() -> disconnectQuietly(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("disconnectQuietly()는 이미 끊긴 세션이면 disconnect()를 호출하지 않는다")
    void disconnectQuietly_skipsDisconnect_whenAlreadyDisconnected() {
        StompSession session = Mockito.mock(StompSession.class);
        when(session.isConnected()).thenReturn(false);

        disconnectQuietly(session);

        verify(session, never()).disconnect();
    }

    @Test
    @DisplayName("disconnectQuietly()는 IllegalStateException을 무시한다")
    void disconnectQuietly_swallowsIllegalStateException() {
        StompSession session = Mockito.mock(StompSession.class);
        when(session.isConnected()).thenReturn(true);
        doThrow(new IllegalStateException("Connection closed")).when(session).disconnect();

        assertThatCode(() -> disconnectQuietly(session)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("disconnectQuietly()는 MessageDeliveryException을 무시한다")
    void disconnectQuietly_swallowsMessageDeliveryException() {
        StompSession session = Mockito.mock(StompSession.class);
        when(session.isConnected()).thenReturn(true);
        doThrow(new MessageDeliveryException("전송 실패")).when(session).disconnect();

        assertThatCode(() -> disconnectQuietly(session)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("disconnectQuietly()는 그 외 예외는 숨기지 않고 전파한다")
    void disconnectQuietly_propagatesOtherExceptions() {
        StompSession session = Mockito.mock(StompSession.class);
        when(session.isConnected()).thenReturn(true);
        doThrow(new RuntimeException("예상 못 한 실패")).when(session).disconnect();

        assertThatThrownBy(() -> disconnectQuietly(session))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("예상 못 한 실패");
    }

    @Test
    @DisplayName("closeAll()은 세션 disconnect 실패와 무관하게 client.stop()과 scheduler.shutdown()을 모두 호출한다")
    void closeAll_runsAllSteps_evenWhenSessionDisconnectFails() {
        StompSession session = Mockito.mock(StompSession.class);
        when(session.isConnected()).thenReturn(true);
        doThrow(new IllegalStateException("Connection closed")).when(session).disconnect();

        WebSocketStompClient client = Mockito.mock(WebSocketStompClient.class);
        ThreadPoolTaskScheduler scheduler = Mockito.mock(ThreadPoolTaskScheduler.class);

        closeAll(client, scheduler, session);

        verify(client).stop();
        verify(scheduler).shutdown();
    }

    @Test
    @DisplayName("closeAll()은 client.stop() 실패와 무관하게 scheduler.shutdown()을 호출한다")
    void closeAll_runsSchedulerShutdown_evenWhenClientStopFails() {
        WebSocketStompClient client = Mockito.mock(WebSocketStompClient.class);
        doThrow(new RuntimeException("stop 실패")).when(client).stop();
        ThreadPoolTaskScheduler scheduler = Mockito.mock(ThreadPoolTaskScheduler.class);

        assertThatThrownBy(() -> closeAll(client, scheduler))
            .isInstanceOf(IllegalStateException.class);

        verify(scheduler).shutdown();
    }

    @Test
    @DisplayName("closeAll()은 무시 대상이 아닌 실패를 모아 마지막에 suppressed로 보고한다")
    void closeAll_collectsNonIgnoredFailures_andThrowsWithSuppressed() {
        WebSocketStompClient client = Mockito.mock(WebSocketStompClient.class);
        RuntimeException stopFailure = new RuntimeException("stop 실패");
        doThrow(stopFailure).when(client).stop();

        ThreadPoolTaskScheduler scheduler = Mockito.mock(ThreadPoolTaskScheduler.class);
        RuntimeException shutdownFailure = new RuntimeException("shutdown 실패");
        doThrow(shutdownFailure).when(scheduler).shutdown();

        assertThatThrownBy(() -> closeAll(client, scheduler))
            .isInstanceOf(IllegalStateException.class)
            .satisfies(error ->
                assertThat(error.getSuppressed()).containsExactly(stopFailure, shutdownFailure));
    }

    @Test
    @DisplayName("closeAll()은 무시 대상 예외만 있으면 정상 종료하고 아무것도 던지지 않는다")
    void closeAll_doesNotThrow_whenOnlyIgnoredExceptionsOccur() {
        StompSession session = Mockito.mock(StompSession.class);
        when(session.isConnected()).thenReturn(true);
        doThrow(new MessageDeliveryException("전송 실패")).when(session).disconnect();

        WebSocketStompClient client = Mockito.mock(WebSocketStompClient.class);
        ThreadPoolTaskScheduler scheduler = Mockito.mock(ThreadPoolTaskScheduler.class);

        assertThatCode(() -> closeAll(client, scheduler, session)).doesNotThrowAnyException();

        verify(client).stop();
        verify(scheduler).shutdown();
    }

    @Test
    @DisplayName("closeAll()은 여러 세션을 모두 정리한다")
    void closeAll_disconnectsAllGivenSessions() {
        StompSession session1 = Mockito.mock(StompSession.class);
        StompSession session2 = Mockito.mock(StompSession.class);
        when(session1.isConnected()).thenReturn(true);
        when(session2.isConnected()).thenReturn(true);

        WebSocketStompClient client = Mockito.mock(WebSocketStompClient.class);
        ThreadPoolTaskScheduler scheduler = Mockito.mock(ThreadPoolTaskScheduler.class);

        closeAll(client, scheduler, session1, session2);

        verify(session1).disconnect();
        verify(session2).disconnect();
    }
}
