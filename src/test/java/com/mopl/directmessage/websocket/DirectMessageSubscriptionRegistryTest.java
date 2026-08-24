package com.mopl.directmessage.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.directmessage.presence.DirectMessagePresenceStore;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

class DirectMessageSubscriptionRegistryTest {

    private static final UUID USER_ID =
        UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

    private static final UUID CONVERSATION_ID =
        UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
        );

    private static final UUID OTHER_CONVERSATION_ID =
        UUID.fromString(
            "33333333-3333-3333-3333-333333333333"
        );

    private final DirectMessagePresenceStore presenceStore =
        mock(DirectMessagePresenceStore.class);

    private final DirectMessageSubscriptionRegistry registry =
        new DirectMessageSubscriptionRegistry(presenceStore);

    @Test
    @DisplayName("대화를 구독하면 활성 대화로 등록된다")
    void activate_success() {
        registry.activate(
            USER_ID,
            CONVERSATION_ID,
            "session-1",
            "subscription-1"
        );

        assertThat(
            registry.isActive(
                USER_ID,
                CONVERSATION_ID
            )
        ).isTrue();

        verify(presenceStore).register(
            USER_ID,
            CONVERSATION_ID,
            "session-1",
            "subscription-1"
        );
    }

    @Test
    @DisplayName("대화 구독을 해제하면 활성 상태에서 제거된다")
    void deactivate_success() {
        registry.activate(
            USER_ID,
            CONVERSATION_ID,
            "session-1",
            "subscription-1"
        );

        registry.deactivate(
            USER_ID,
            CONVERSATION_ID,
            "session-1",
            "subscription-1"
        );

        assertThat(
            registry.isActive(
                USER_ID,
                CONVERSATION_ID
            )
        ).isFalse();

        verify(presenceStore).unregister(
            USER_ID,
            CONVERSATION_ID,
            "session-1",
            "subscription-1"
        );
    }

    @Test
    @DisplayName("동일 세션의 구독 등록과 해제를 Redis 전이 순서대로 처리")
    void activateAndDeactivate_concurrent_preservesOrder()
        throws Exception {

        CountDownLatch registerStarted =
            new CountDownLatch(1);

        CountDownLatch releaseRegister =
            new CountDownLatch(1);

        CountDownLatch unregisterCalled =
            new CountDownLatch(1);

        CountDownLatch deactivateStarted =
            new CountDownLatch(1);

        AtomicBoolean redisActive =
            new AtomicBoolean(false);

        doAnswer(invocation -> {
            registerStarted.countDown();

            assertThat(
                releaseRegister.await(
                    5,
                    TimeUnit.SECONDS
                )
            ).isTrue();

            redisActive.set(true);
            return null;
        })
            .when(presenceStore)
            .register(
                USER_ID,
                CONVERSATION_ID,
                "session-1",
                "subscription-1"
            );

        doAnswer(invocation -> {
            redisActive.set(false);
            unregisterCalled.countDown();
            return true;
        })
            .when(presenceStore)
            .unregister(
                USER_ID,
                CONVERSATION_ID,
                "session-1",
                "subscription-1"
            );

        ExecutorService executor =
            Executors.newFixedThreadPool(2);

        try {
            Future<?> activateResult =
                executor.submit(() ->
                    registry.activate(
                        USER_ID,
                        CONVERSATION_ID,
                        "session-1",
                        "subscription-1"
                    )
                );

            assertThat(
                registerStarted.await(
                    5,
                    TimeUnit.SECONDS
                )
            ).isTrue();

            Future<?> deactivateResult =
                executor.submit(() -> {
                    deactivateStarted.countDown();

                    registry.deactivate(
                        USER_ID,
                        CONVERSATION_ID,
                        "session-1",
                        "subscription-1"
                    );
                });

            assertThat(
                deactivateStarted.await(
                    5,
                    TimeUnit.SECONDS
                )
            ).isTrue();

            assertThat(
                unregisterCalled.await(
                    300,
                    TimeUnit.MILLISECONDS
                )
            ).isFalse();

            releaseRegister.countDown();

            activateResult.get(
                5,
                TimeUnit.SECONDS
            );

            deactivateResult.get(
                5,
                TimeUnit.SECONDS
            );

            assertThat(redisActive.get())
                .isFalse();

            assertThat(
                registry.isActive(
                    USER_ID,
                    CONVERSATION_ID
                )
            ).isFalse();
        } finally {
            releaseRegister.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("같은 대화의 다른 세션이 남아 있으면 활성 상태를 유지한다")
    void deactivate_otherSessionRemains_active() {
        registry.activate(
            USER_ID,
            CONVERSATION_ID,
            "session-1",
            "subscription-1"
        );

        registry.activate(
            USER_ID,
            CONVERSATION_ID,
            "session-2",
            "subscription-2"
        );

        registry.deactivate(
            USER_ID,
            CONVERSATION_ID,
            "session-1",
            "subscription-1"
        );

        assertThat(
            registry.isActive(
                USER_ID,
                CONVERSATION_ID
            )
        ).isTrue();
    }

    @Test
    @DisplayName("연결이 종료되면 해당 세션의 활성 대화를 제거한다")
    void deactivateSession_success() {
        registry.activate(
            USER_ID,
            CONVERSATION_ID,
            "session-1",
            "subscription-1"
        );

        registry.deactivateSession(
            USER_ID,
            "session-1"
        );

        assertThat(
            registry.isActive(
                USER_ID,
                CONVERSATION_ID
            )
        ).isFalse();

        verify(presenceStore).unregisterSession(
            USER_ID,
            "session-1"
        );
    }

    @Test
    @DisplayName("다른 서버의 Redis 활성 상태도 활성 대화로 조회")
    void isActive_remotePresence_returnsTrue() {
        when(
            presenceStore.isActive(
                USER_ID,
                CONVERSATION_ID
            )
        ).thenReturn(true);

        assertThat(
            registry.isActive(
                USER_ID,
                CONVERSATION_ID
            )
        ).isTrue();

        verify(presenceStore).isActive(
            USER_ID,
            CONVERSATION_ID
        );
    }

    @Test
    @DisplayName("Redis 등록 실패에도 현재 서버의 활성 상태를 유지")
    void activate_redisFailure_keepsLocalState() {
        doThrow(
            new RedisConnectionFailureException(
                "Redis 연결 실패"
            )
        )
            .when(presenceStore)
            .register(
                USER_ID,
                CONVERSATION_ID,
                "session-1",
                "subscription-1"
            );

        registry.activate(
            USER_ID,
            CONVERSATION_ID,
            "session-1",
            "subscription-1"
        );

        assertThat(
            registry.isActive(
                USER_ID,
                CONVERSATION_ID
            )
        ).isTrue();

        verify(
            presenceStore,
            never()
        ).isActive(
            USER_ID,
            CONVERSATION_ID
        );
    }

    @Test
    @DisplayName("로컬 상태가 없고 Redis 조회에 실패하면 비활성 상태로 처리")
    void isActive_redisFailure_returnsFalse() {
        when(
            presenceStore.isActive(
                USER_ID,
                CONVERSATION_ID
            )
        ).thenThrow(
            new RedisConnectionFailureException(
                "Redis 연결 실패"
            )
        );

        assertThat(
            registry.isActive(
                USER_ID,
                CONVERSATION_ID
            )
        ).isFalse();
    }

    @Test
    @DisplayName("같은 세션의 여러 DM 구독은 TTL을 한 번만 갱신")
    void renewPresence_sameSession_renewsOnce() {
        registry.activate(
            USER_ID,
            CONVERSATION_ID,
            "session-1",
            "subscription-1"
        );

        registry.activate(
            USER_ID,
            OTHER_CONVERSATION_ID,
            "session-1",
            "subscription-2"
        );

        clearInvocations(presenceStore);

        registry.renewPresence();

        verify(
            presenceStore,
            times(1)
        ).renewSession(
            USER_ID,
            "session-1"
        );
    }

    @Test
    @DisplayName("Redis 등록 누락은 다음 TTL 갱신에서 로컬 구독으로 복구")
    void renewPresence_missingRegistration_restoresPresence() {
        doThrow(
            new RedisConnectionFailureException(
                "Redis 연결 실패"
            )
        )
            .doNothing()
            .when(presenceStore)
            .register(
                USER_ID,
                CONVERSATION_ID,
                "session-1",
                "subscription-1"
            );

        when(
            presenceStore.renewSession(
                USER_ID,
                "session-1"
            )
        ).thenReturn(0L);

        registry.activate(
            USER_ID,
            CONVERSATION_ID,
            "session-1",
            "subscription-1"
        );

        registry.renewPresence();

        verify(
            presenceStore,
            times(2)
        ).register(
            USER_ID,
            CONVERSATION_ID,
            "session-1",
            "subscription-1"
        );
    }

    @Test
    @DisplayName("Redis TTL 갱신 실패가 다른 로컬 구독 상태에 영향을 주지 않음")
    void renewPresence_redisFailure_keepsLocalState() {
        registry.activate(
            USER_ID,
            CONVERSATION_ID,
            "session-1",
            "subscription-1"
        );

        doThrow(
            new RedisConnectionFailureException(
                "Redis 연결 실패"
            )
        )
            .when(presenceStore)
            .renewSession(
                USER_ID,
                "session-1"
            );

        registry.renewPresence();

        assertThat(
            registry.isActive(
                USER_ID,
                CONVERSATION_ID
            )
        ).isTrue();
    }
}
