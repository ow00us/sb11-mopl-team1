package com.mopl.watchingsession.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.watchingsession.dto.SubscriptionConsumeResult;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

class WatchSubscriptionAttributesTest {

    private static final UUID CONTENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_CONTENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private StompHeaderAccessor createAccessor(String subscriptionId, Map<String, Object> sessionAttributes) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSubscriptionId(subscriptionId);
        accessor.setSessionAttributes(sessionAttributes);
        accessor.setLeaveMutable(true);
        return accessor;
    }

    @Test
    @DisplayName("subscriptionId와 sessionAttributes가 있으면 매핑 저장에 성공")
    void put_success_whenSubscriptionIdAndSessionAttributesExist() {
        StompHeaderAccessor accessor = createAccessor("sub-1", new HashMap<>());

        boolean result = WatchSubscriptionAttributes.put(accessor, CONTENT_ID);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("subscriptionId가 없으면 매핑 저장에 실패")
    void put_fails_whenSubscriptionIdIsNull() {
        StompHeaderAccessor accessor = createAccessor(null, new HashMap<>());

        boolean result = WatchSubscriptionAttributes.put(accessor, CONTENT_ID);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("sessionAttributes가 없으면 매핑 저장에 실패")
    void put_fails_whenSessionAttributesIsNull() {
        StompHeaderAccessor accessor = createAccessor("sub-1", null);

        boolean result = WatchSubscriptionAttributes.put(accessor, CONTENT_ID);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("SUBSCRIBE 직후에는 해당 subscriptionId가 활성 상태")
    void isActive_true_rightAfterSubscribe() {
        StompHeaderAccessor accessor = createAccessor("sub-1", new HashMap<>());

        WatchSubscriptionAttributes.put(accessor, CONTENT_ID);

        assertThat(WatchSubscriptionAttributes.isActive(accessor)).isTrue();
    }

    @Test
    @DisplayName("같은 연결에서 재구독하면 이전 subscriptionId는 비활성, 새 subscriptionId는 활성")
    void isActive_reflectsMostRecentSubscribeOnly() {
        Map<String, Object> sessionAttributes = new HashMap<>();
        StompHeaderAccessor sub1 = createAccessor("sub-1", sessionAttributes);
        StompHeaderAccessor sub2 = createAccessor("sub-2", sessionAttributes);

        WatchSubscriptionAttributes.put(sub1, CONTENT_ID);
        WatchSubscriptionAttributes.put(sub2, CONTENT_ID);

        assertThat(WatchSubscriptionAttributes.isActive(sub1)).isFalse();
        assertThat(WatchSubscriptionAttributes.isActive(sub2)).isTrue();
    }

    @Test
    @DisplayName("같은 콘텐츠를 같은 subscriptionId로 다시 put해도 활성 상태 유지")
    void isActive_true_whenSameSubscriptionIdPutAgain() {
        Map<String, Object> sessionAttributes = new HashMap<>();
        StompHeaderAccessor accessor = createAccessor("sub-1", sessionAttributes);

        WatchSubscriptionAttributes.put(accessor, CONTENT_ID);
        WatchSubscriptionAttributes.put(accessor, OTHER_CONTENT_ID);

        assertThat(WatchSubscriptionAttributes.isActive(accessor)).isTrue();
    }

    @Test
    @DisplayName("subscriptionId가 null이면 비활성으로 처리")
    void isActive_false_whenSubscriptionIdIsNull() {
        StompHeaderAccessor accessor = createAccessor(null, new HashMap<>());

        assertThat(WatchSubscriptionAttributes.isActive(accessor)).isFalse();
    }

    @Test
    @DisplayName("sessionAttributes가 없으면 비활성으로 처리")
    void isActive_false_whenSessionAttributesIsNull() {
        StompHeaderAccessor accessor = createAccessor("sub-1", null);

        assertThat(WatchSubscriptionAttributes.isActive(accessor)).isFalse();
    }

    @Test
    @DisplayName("한 번도 put되지 않은 subscriptionId는 비활성으로 처리")
    void isActive_false_whenNeverPut() {
        StompHeaderAccessor accessor = createAccessor("sub-never-put", new HashMap<>());

        assertThat(WatchSubscriptionAttributes.isActive(accessor)).isFalse();
    }

    @Test
    @DisplayName("consume으로 활성 구독을 소비하면 ACTIVE 결과를 반환하고, 이후 isActive는 false로 바뀜")
    void consume_returnsActive_andIsActiveBecomesFalse_afterConsumingActiveSubscription() {
        Map<String, Object> sessionAttributes = new HashMap<>();
        StompHeaderAccessor putAccessor = createAccessor("sub-1", sessionAttributes);
        WatchSubscriptionAttributes.put(putAccessor, CONTENT_ID);

        StompHeaderAccessor consumeAccessor = createAccessor("sub-1", sessionAttributes);
        SubscriptionConsumeResult result = WatchSubscriptionAttributes.consume(consumeAccessor);

        assertThat(result.wasActive()).isTrue();
        assertThat(result.contentId()).isEqualTo(CONTENT_ID);

        StompHeaderAccessor checkAccessor = createAccessor("sub-1", sessionAttributes);
        assertThat(WatchSubscriptionAttributes.isActive(checkAccessor)).isFalse();
    }

    @Test
    @DisplayName("consume으로 낡은 구독(sub-1)을 소비하면 STALE 결과를 반환하고, 현재 활성 구독(sub-2)의 isActive는 계속 true")
    void consume_returnsStale_forNonActiveSubscription_andDoesNotAffectCurrentActiveSubscription() {
        Map<String, Object> sessionAttributes = new HashMap<>();
        StompHeaderAccessor sub1PutAccessor = createAccessor("sub-1", sessionAttributes);
        WatchSubscriptionAttributes.put(sub1PutAccessor, CONTENT_ID);

        StompHeaderAccessor sub2PutAccessor = createAccessor("sub-2", sessionAttributes);
        WatchSubscriptionAttributes.put(sub2PutAccessor, CONTENT_ID);

        StompHeaderAccessor sub1ConsumeAccessor = createAccessor("sub-1", sessionAttributes);
        SubscriptionConsumeResult result = WatchSubscriptionAttributes.consume(sub1ConsumeAccessor);

        assertThat(result.wasActive()).isFalse();
        assertThat(result.hasMapping()).isTrue();
        assertThat(result.contentId()).isEqualTo(CONTENT_ID);

        // sub-1을 소비해도 현재 활성 구독(sub-2)의 activeSubscriptionId는 그대로 유지되어야 함
        StompHeaderAccessor sub2CheckAccessor = createAccessor("sub-2", sessionAttributes);
        assertThat(WatchSubscriptionAttributes.isActive(sub2CheckAccessor)).isTrue();
    }

    @Test
    @DisplayName("매핑되지 않은 subscriptionId를 consume하면 NO_MAPPING을 반환")
    void consume_returnsNoMapping_whenNoMappingExists() {
        StompHeaderAccessor accessor = createAccessor("sub-unknown", new HashMap<>());

        SubscriptionConsumeResult result = WatchSubscriptionAttributes.consume(accessor);

        assertThat(result.hasMapping()).isFalse();
        assertThat(result.wasActive()).isFalse();
    }

    @Test
    @DisplayName("subscriptionId가 없으면 consume은 NO_MAPPING을 반환")
    void consume_returnsNoMapping_whenSubscriptionIdIsNull() {
        StompHeaderAccessor accessor = createAccessor(null, new HashMap<>());

        SubscriptionConsumeResult result = WatchSubscriptionAttributes.consume(accessor);

        assertThat(result.hasMapping()).isFalse();
    }

    @Test
    @DisplayName("sessionAttributes가 없으면 consume은 NO_MAPPING을 반환")
    void consume_returnsNoMapping_whenSessionAttributesIsNull() {
        StompHeaderAccessor accessor = createAccessor("sub-1", null);

        SubscriptionConsumeResult result = WatchSubscriptionAttributes.consume(accessor);

        assertThat(result.hasMapping()).isFalse();
    }


}
