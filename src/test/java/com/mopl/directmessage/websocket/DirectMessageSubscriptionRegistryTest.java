package com.mopl.directmessage.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DirectMessageSubscriptionRegistryTest {

    private static final UUID USER_ID =
        UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

    private static final UUID CONVERSATION_ID =
        UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
        );

    private final DirectMessageSubscriptionRegistry registry =
        new DirectMessageSubscriptionRegistry();

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
    }
}
