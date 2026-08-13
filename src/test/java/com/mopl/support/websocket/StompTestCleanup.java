package com.mopl.support.websocket;

import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * WebSocket·STOMP 통합 테스트의 자원 정리 공통 유틸.
 *
 * 정리 단계에서 발생하는 연결 종료 경합은 검증 대상이 아니므로 무시하되,
 * 그 외의 정리 실패는 숨기지 않는다.
 */
public final class StompTestCleanup {

    private StompTestCleanup() {
    }

    /**
     * 세션을 종료하되, 서버가 먼저 연결을 닫아 발생하는 경합 예외만 무시한다.
     */
    public static void disconnectQuietly(@Nullable StompSession session) {
        if (session == null || !session.isConnected()) {
            return;
        }
        try {
            session.disconnect();
        } catch (IllegalStateException | MessageDeliveryException ignored) {
            /*
             * isConnected()는 connected 플래그를, disconnect()는 connection 필드를 본다.
             * 두 값이 정리되는 사이에 끼면 connection == null 이면 IllegalStateException,
             * 전송 단계에서 끊기면 MessageDeliveryException이 발생한다.
             * 검증이 끝난 뒤의 정리 단계이므로 이 두 가지만 무시한다.
             */
        }
    }

    /**
     * 세션·클라이언트·스케줄러를 순서대로 정리한다.
     *
     * 앞 단계가 실패해도 뒤 단계를 건너뛰지 않는다. 커넥션과 스레드풀 누수가
     * 다음 테스트로 전파되는 것을 막기 위해서다. 무시 대상이 아닌 실패는
     * 전부 모아 마지막에 한 번 보고한다.
     */
    public static void closeAll(
        @Nullable WebSocketStompClient client,
        @Nullable ThreadPoolTaskScheduler scheduler,
        @Nullable StompSession... sessions
    ) {
        List<Throwable> failures = new ArrayList<>();

        if (sessions != null) {
            for (StompSession session : sessions) {
                runCollecting(() -> disconnectQuietly(session), failures);
            }
        }
        runCollecting(() -> {
            if (client != null) {
                client.stop();
            }
        }, failures);
        runCollecting(() -> {
            if (scheduler != null) {
                scheduler.shutdown();
            }
        }, failures);

        if (!failures.isEmpty()) {
            IllegalStateException error = new IllegalStateException("WebSocket 테스트 자원 정리에 실패했습니다.");
            failures.forEach(error::addSuppressed);
            throw error;
        }
    }

    private static void runCollecting(Runnable step, List<Throwable> failures) {
        try {
            step.run();
        } catch (RuntimeException | AssertionError failure) {
            failures.add(failure);
        }
    }

}
