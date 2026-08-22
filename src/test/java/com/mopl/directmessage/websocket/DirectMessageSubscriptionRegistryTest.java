package com.mopl.directmessage.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.directmessage.presence.DirectMessagePresenceStore;
import java.util.UUID;
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
