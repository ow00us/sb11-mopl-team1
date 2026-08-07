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
    @DisplayName("put만 호출하고 activate를 호출하지 않으면 아직 활성 상태가 아니다")
    void put_alone_doesNotActivate() {
        StompHeaderAccessor accessor = createAccessor("sub-1", new HashMap<>());

        WatchSubscriptionAttributes.put(accessor, CONTENT_ID);

        // put()은 매핑만 저장할 뿐, 활성 전환은 activate()가 별도로 담당한다.
        assertThat(WatchSubscriptionAttributes.isActive(accessor)).isFalse();
    }

    @Test
    @DisplayName("activate 호출 후에는 해당 subscriptionId가 활성 상태")
    void activate_makesSubscriptionActive() {
        StompHeaderAccessor accessor = createAccessor("sub-1", new HashMap<>());
        WatchSubscriptionAttributes.put(accessor, CONTENT_ID);

        WatchSubscriptionAttributes.activate(accessor);

        assertThat(WatchSubscriptionAttributes.isActive(accessor)).isTrue();
    }

    @Test
    @DisplayName("같은 연결에서 재구독 후 activate하면 이전 구독은 비활성, 새 구독은 활성으로 전환")
    void activate_reflectsMostRecentActivationOnly() {
        Map<String, Object> sessionAttributes = new HashMap<>();
        StompHeaderAccessor sub1 = createAccessor("sub-1", sessionAttributes);
        StompHeaderAccessor sub2 = createAccessor("sub-2", sessionAttributes);

        WatchSubscriptionAttributes.put(sub1, CONTENT_ID);
        WatchSubscriptionAttributes.activate(sub1);

        WatchSubscriptionAttributes.put(sub2, CONTENT_ID);
        WatchSubscriptionAttributes.activate(sub2);

        assertThat(WatchSubscriptionAttributes.isActive(sub1)).isFalse();
        assertThat(WatchSubscriptionAttributes.isActive(sub2)).isTrue();
    }

    @Test
    @DisplayName("재구독의 put만 하고 activate하지 않으면 이전 구독이 계속 활성 상태로 유지된다")
    void activate_notCalled_keepsPreviousSubscriptionActive() {
        // start() 실패 시나리오를 재현: sub-1은 정상적으로 활성화됐지만,
        // 재구독한 sub-2는 put까지만 되고 activate는 호출되지 않는다(=start() 실패로 activate 전 롤백).
        Map<String, Object> sessionAttributes = new HashMap<>();
        StompHeaderAccessor sub1 = createAccessor("sub-1", sessionAttributes);
        WatchSubscriptionAttributes.put(sub1, CONTENT_ID);
        WatchSubscriptionAttributes.activate(sub1);

        StompHeaderAccessor sub2 = createAccessor("sub-2", sessionAttributes);
        WatchSubscriptionAttributes.put(sub2, OTHER_CONTENT_ID);
        // activate(sub2)를 의도적으로 호출하지 않음

        assertThat(WatchSubscriptionAttributes.isActive(sub1)).isTrue();
        assertThat(WatchSubscriptionAttributes.isActive(sub2)).isFalse();
    }

    @Test
    @DisplayName("subscriptionId가 없으면 activate는 아무 효과가 없다")
    void activate_doesNothing_whenSubscriptionIdIsNull() {
        StompHeaderAccessor accessor = createAccessor(null, new HashMap<>());

        WatchSubscriptionAttributes.activate(accessor);

        assertThat(WatchSubscriptionAttributes.isActive(accessor)).isFalse();
    }

    @Test
    @DisplayName("sessionAttributes가 없으면 activate는 아무 효과가 없다")
    void activate_doesNothing_whenSessionAttributesIsNull() {
        StompHeaderAccessor accessor = createAccessor("sub-1", null);

        WatchSubscriptionAttributes.activate(accessor);

        assertThat(WatchSubscriptionAttributes.isActive(accessor)).isFalse();
    }

    @Test
    @DisplayName("subscriptionId가 없으면 비활성으로 처리")
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
    @DisplayName("한 번도 activate되지 않은 subscriptionId는 비활성으로 처리")
    void isActive_false_whenNeverActivated() {
        StompHeaderAccessor accessor = createAccessor("sub-never-activated", new HashMap<>());

        assertThat(WatchSubscriptionAttributes.isActive(accessor)).isFalse();
    }

    @Test
    @DisplayName("consume으로 활성 구독을 소비하면 ACTIVE 결과를 반환하고, 이후 isActive는 false로 바뀐다")
    void consume_returnsActive_andIsActiveBecomesFalse_afterConsumingActiveSubscription() {
        Map<String, Object> sessionAttributes = new HashMap<>();
        StompHeaderAccessor putAccessor = createAccessor("sub-1", sessionAttributes);
        WatchSubscriptionAttributes.put(putAccessor, CONTENT_ID);
        WatchSubscriptionAttributes.activate(putAccessor);

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
        WatchSubscriptionAttributes.activate(sub1PutAccessor);

        StompHeaderAccessor sub2PutAccessor = createAccessor("sub-2", sessionAttributes);
        WatchSubscriptionAttributes.put(sub2PutAccessor, CONTENT_ID);
        WatchSubscriptionAttributes.activate(sub2PutAccessor);

        StompHeaderAccessor sub1ConsumeAccessor = createAccessor("sub-1", sessionAttributes);
        SubscriptionConsumeResult result = WatchSubscriptionAttributes.consume(sub1ConsumeAccessor);

        assertThat(result.wasActive()).isFalse();
        assertThat(result.hasMapping()).isTrue();
        assertThat(result.contentId()).isEqualTo(CONTENT_ID);

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

    @Test
    @DisplayName("put만 되고 activate되지 않은 구독을 consume하면 STALE로 처리된다 (활성화 전 롤백 시나리오)")
    void consume_returnsStale_whenMappedButNeverActivated() {
        StompHeaderAccessor accessor = createAccessor("sub-1", new HashMap<>());
        WatchSubscriptionAttributes.put(accessor, CONTENT_ID);
        // activate를 호출하지 않음 (start() 실패로 인한 롤백 시나리오)

        SubscriptionConsumeResult result = WatchSubscriptionAttributes.consume(accessor);

        assertThat(result.hasMapping()).isTrue();
        assertThat(result.wasActive()).isFalse();
        assertThat(result.contentId()).isEqualTo(CONTENT_ID);
    }
}
