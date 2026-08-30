package com.mopl.directmessage.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

class DirectMessageSubscriptionAttributesTest {

    private static final UUID CONVERSATION_ID =
        UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

    private static final UUID OTHER_CONVERSATION_ID =
        UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
        );

    @Test
    @DisplayName("subscriptionId와 세션 속성이 있으면 DM 구독 매핑을 저장")
    void put_success() {
        // given
        StompHeaderAccessor accessor =
            createAccessor(
                "subscription-1",
                new HashMap<>()
            );

        // when
        boolean result =
            DirectMessageSubscriptionAttributes.put(
                accessor,
                CONVERSATION_ID
            );

        // then
        assertThat(result).isTrue();
        assertThat(
            DirectMessageSubscriptionAttributes.remove(
                accessor
            )
        ).isEqualTo(CONVERSATION_ID);
    }

    @Test
    @DisplayName("subscriptionId가 없으면 DM 구독 매핑을 저장하지 않음")
    void put_subscriptionIdMissing_returnsFalse() {
        // given
        StompHeaderAccessor accessor =
            createAccessor(
                null,
                new HashMap<>()
            );

        // when
        boolean result =
            DirectMessageSubscriptionAttributes.put(
                accessor,
                CONVERSATION_ID
            );

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("세션 속성이 없으면 DM 구독 매핑을 저장하지 않음")
    void put_sessionAttributesMissing_returnsFalse() {
        // given
        StompHeaderAccessor accessor =
            createAccessor(
                "subscription-1",
                null
            );

        // when
        boolean result =
            DirectMessageSubscriptionAttributes.put(
                accessor,
                CONVERSATION_ID
            );

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("같은 세션의 여러 DM 구독 매핑을 독립적으로 관리")
    void put_multipleSubscriptions_managesIndependently() {
        // given
        Map<String, Object> sessionAttributes =
            new HashMap<>();

        StompHeaderAccessor firstAccessor =
            createAccessor(
                "subscription-1",
                sessionAttributes
            );

        StompHeaderAccessor secondAccessor =
            createAccessor(
                "subscription-2",
                sessionAttributes
            );

        DirectMessageSubscriptionAttributes.put(
            firstAccessor,
            CONVERSATION_ID
        );

        DirectMessageSubscriptionAttributes.put(
            secondAccessor,
            OTHER_CONVERSATION_ID
        );

        // when & then
        assertThat(
            DirectMessageSubscriptionAttributes.remove(
                firstAccessor
            )
        ).isEqualTo(CONVERSATION_ID);

        assertThat(
            DirectMessageSubscriptionAttributes.remove(
                secondAccessor
            )
        ).isEqualTo(OTHER_CONVERSATION_ID);
    }

    @Test
    @DisplayName("이미 제거한 DM 구독 매핑은 다시 조회되지 않음")
    void remove_alreadyRemoved_returnsNull() {
        // given
        StompHeaderAccessor accessor =
            createAccessor(
                "subscription-1",
                new HashMap<>()
            );

        DirectMessageSubscriptionAttributes.put(
            accessor,
            CONVERSATION_ID
        );

        DirectMessageSubscriptionAttributes.remove(
            accessor
        );

        // when
        UUID result =
            DirectMessageSubscriptionAttributes.remove(
                accessor
            );

        // then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("subscriptionId가 없으면 DM 구독 매핑을 제거하지 않음")
    void remove_subscriptionIdMissing_returnsNull() {
        // given
        StompHeaderAccessor accessor =
            createAccessor(
                null,
                new HashMap<>()
            );

        // when
        UUID result =
            DirectMessageSubscriptionAttributes.remove(
                accessor
            );

        // then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("세션 속성이 없으면 DM 구독 매핑을 제거하지 않음")
    void remove_sessionAttributesMissing_returnsNull() {
        // given
        StompHeaderAccessor accessor =
            createAccessor(
                "subscription-1",
                null
            );

        // when
        UUID result =
            DirectMessageSubscriptionAttributes.remove(
                accessor
            );

        // then
        assertThat(result).isNull();
    }

    private StompHeaderAccessor createAccessor(
        String subscriptionId,
        Map<String, Object> sessionAttributes
    ) {
        StompHeaderAccessor accessor =
            StompHeaderAccessor.create(
                StompCommand.SUBSCRIBE
            );

        accessor.setSubscriptionId(
            subscriptionId
        );

        accessor.setSessionAttributes(
            sessionAttributes
        );

        return accessor;
    }
}
