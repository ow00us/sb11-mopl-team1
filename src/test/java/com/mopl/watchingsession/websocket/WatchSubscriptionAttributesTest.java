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

    // ── put() ────────────────────────────────────────────────────────────

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
        // 활성 여부는 currentActiveSubscriptionId()로 확인한다 (isActive()는 더 이상 없음).
        assertThat(WatchSubscriptionAttributes.currentActiveSubscriptionId(accessor)).isNull();
    }

    // ── activate() / currentActiveSubscriptionId() ─────────────────────────

    @Test
    @DisplayName("activate 호출 후에는 currentActiveSubscriptionId가 해당 subscriptionId를 반환한다")
    void activate_makesSubscriptionIdCurrentActive() {
        StompHeaderAccessor accessor = createAccessor("sub-1", new HashMap<>());
        WatchSubscriptionAttributes.put(accessor, CONTENT_ID);

        WatchSubscriptionAttributes.activate(accessor);

        assertThat(WatchSubscriptionAttributes.currentActiveSubscriptionId(accessor)).isEqualTo("sub-1");
    }

    @Test
    @DisplayName("같은 연결에서 재구독 후 activate하면 currentActiveSubscriptionId는 가장 최근 것만 가리킨다")
    void activate_reflectsMostRecentActivationOnly() {
        Map<String, Object> sessionAttributes = new HashMap<>();
        StompHeaderAccessor sub1 = createAccessor("sub-1", sessionAttributes);
        StompHeaderAccessor sub2 = createAccessor("sub-2", sessionAttributes);

        WatchSubscriptionAttributes.put(sub1, CONTENT_ID);
        WatchSubscriptionAttributes.activate(sub1);

        WatchSubscriptionAttributes.put(sub2, CONTENT_ID);
        WatchSubscriptionAttributes.activate(sub2);

        assertThat(WatchSubscriptionAttributes.currentActiveSubscriptionId(sub1)).isEqualTo("sub-2");
        assertThat(WatchSubscriptionAttributes.currentActiveSubscriptionId(sub2)).isEqualTo("sub-2");
    }

    @Test
    @DisplayName("재구독의 put만 하고 activate하지 않으면 currentActiveSubscriptionId는 이전 구독을 계속 가리킨다")
    void activate_notCalled_keepsPreviousSubscriptionAsCurrentActive() {
        // start() 실패 시나리오를 재현: sub-1은 정상적으로 활성화됐지만,
        // 재구독한 sub-2는 put까지만 되고 activate는 호출되지 않는다(=start() 실패로 activate 전 롤백).
        Map<String, Object> sessionAttributes = new HashMap<>();
        StompHeaderAccessor sub1 = createAccessor("sub-1", sessionAttributes);
        WatchSubscriptionAttributes.put(sub1, CONTENT_ID);
        WatchSubscriptionAttributes.activate(sub1);

        StompHeaderAccessor sub2 = createAccessor("sub-2", sessionAttributes);
        WatchSubscriptionAttributes.put(sub2, OTHER_CONTENT_ID);
        // activate(sub2)를 의도적으로 호출하지 않음

        assertThat(WatchSubscriptionAttributes.currentActiveSubscriptionId(sub1)).isEqualTo("sub-1");
    }

    @Test
    @DisplayName("subscriptionId가 없으면 activate는 아무 효과가 없다")
    void activate_doesNothing_whenSubscriptionIdIsNull() {
        StompHeaderAccessor accessor = createAccessor(null, new HashMap<>());

        WatchSubscriptionAttributes.activate(accessor);

        assertThat(WatchSubscriptionAttributes.currentActiveSubscriptionId(accessor)).isNull();
    }

    @Test
    @DisplayName("sessionAttributes가 없으면 activate는 아무 효과가 없다")
    void activate_doesNothing_whenSessionAttributesIsNull() {
        StompHeaderAccessor accessor = createAccessor("sub-1", null);

        WatchSubscriptionAttributes.activate(accessor);

        assertThat(WatchSubscriptionAttributes.currentActiveSubscriptionId(accessor)).isNull();
    }

    @Test
    @DisplayName("sessionAttributes가 없으면 currentActiveSubscriptionId는 null을 반환")
    void currentActiveSubscriptionId_null_whenSessionAttributesIsNull() {
        StompHeaderAccessor accessor = createAccessor("sub-1", null);

        assertThat(WatchSubscriptionAttributes.currentActiveSubscriptionId(accessor)).isNull();
    }

    @Test
    @DisplayName("한 번도 activate되지 않았으면 currentActiveSubscriptionId는 null을 반환")
    void currentActiveSubscriptionId_null_whenNeverActivated() {
        StompHeaderAccessor accessor = createAccessor("sub-never-activated", new HashMap<>());

        assertThat(WatchSubscriptionAttributes.currentActiveSubscriptionId(accessor)).isNull();
    }

    // ── consume() ────────────────────────────────────────────────────────

    @Test
    @DisplayName("consume으로 매핑된 subscriptionId를 소비하면 contentId를 담은 결과를 반환하고, 매핑에서 제거된다")
    void consume_success_returnsMappedContentIdAndRemovesMapping() {
        Map<String, Object> sessionAttributes = new HashMap<>();
        StompHeaderAccessor putAccessor = createAccessor("sub-1", sessionAttributes);
        WatchSubscriptionAttributes.put(putAccessor, CONTENT_ID);
        WatchSubscriptionAttributes.activate(putAccessor);

        StompHeaderAccessor consumeAccessor = createAccessor("sub-1", sessionAttributes);
        SubscriptionConsumeResult result = WatchSubscriptionAttributes.consume(consumeAccessor);

        assertThat(result.hasMapping()).isTrue();
        assertThat(result.contentId()).isEqualTo(CONTENT_ID);

        // 같은 subscriptionId를 다시 consume하면 이미 제거되어 매핑이 없어야 한다.
        StompHeaderAccessor secondConsumeAccessor = createAccessor("sub-1", sessionAttributes);
        SubscriptionConsumeResult secondResult = WatchSubscriptionAttributes.consume(secondConsumeAccessor);
        assertThat(secondResult.hasMapping()).isFalse();
    }

    @Test
    @DisplayName("같은 연결에서 재구독한 뒤 낡은 구독(sub-1)을 consume해도 매핑된 contentId는 정상적으로 반환된다 (활성 여부와 무관)")
    void consume_returnsMapping_regardlessOfActiveStatus() {
        // consume()은 더 이상 활성 여부를 판정하지 않는다.
        // "낡은 구독인지"의 최종 판정은 WatchingSessionService.end()의 소유권 비교가 전담한다.
        Map<String, Object> sessionAttributes = new HashMap<>();
        StompHeaderAccessor sub1PutAccessor = createAccessor("sub-1", sessionAttributes);
        WatchSubscriptionAttributes.put(sub1PutAccessor, CONTENT_ID);
        WatchSubscriptionAttributes.activate(sub1PutAccessor);

        StompHeaderAccessor sub2PutAccessor = createAccessor("sub-2", sessionAttributes);
        WatchSubscriptionAttributes.put(sub2PutAccessor, CONTENT_ID);
        WatchSubscriptionAttributes.activate(sub2PutAccessor);

        StompHeaderAccessor sub1ConsumeAccessor = createAccessor("sub-1", sessionAttributes);
        SubscriptionConsumeResult result = WatchSubscriptionAttributes.consume(sub1ConsumeAccessor);

        assertThat(result.hasMapping()).isTrue();
        assertThat(result.contentId()).isEqualTo(CONTENT_ID);

        // sub-1을 consume해도 sub-2가 currentActiveSubscriptionId로 남아있어야 한다.
        StompHeaderAccessor sub2CheckAccessor = createAccessor("sub-2", sessionAttributes);
        assertThat(WatchSubscriptionAttributes.currentActiveSubscriptionId(sub2CheckAccessor)).isEqualTo("sub-2");
    }

    @Test
    @DisplayName("매핑되지 않은 subscriptionId를 consume하면 NO_MAPPING을 반환")
    void consume_returnsNoMapping_whenNoMappingExists() {
        StompHeaderAccessor accessor = createAccessor("sub-unknown", new HashMap<>());

        SubscriptionConsumeResult result = WatchSubscriptionAttributes.consume(accessor);

        assertThat(result.hasMapping()).isFalse();
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
    @DisplayName("put만 되고 activate되지 않은 구독도 consume하면 매핑된 contentId가 정상적으로 반환된다")
    void consume_returnsMapping_whenMappedButNeverActivated() {
        // activate 전에 실패 롤백된 시나리오(WatchingSessionSubscribeListener의 catch 블록)도
        // consume()이 매핑 자체는 정상적으로 정리할 수 있어야 한다.
        StompHeaderAccessor accessor = createAccessor("sub-1", new HashMap<>());
        WatchSubscriptionAttributes.put(accessor, CONTENT_ID);
        // activate를 호출하지 않음

        SubscriptionConsumeResult result = WatchSubscriptionAttributes.consume(accessor);

        assertThat(result.hasMapping()).isTrue();
        assertThat(result.contentId()).isEqualTo(CONTENT_ID);
    }

    @Test
    @DisplayName("sessionAttributes에 저장된 활성 ID 값이 String 타입이 아니면 안전하게 null을 반환")
    void currentActiveSubscriptionId_null_whenValueIsNotString() {
        // given: 의도적으로 String이 아닌 값(예: Integer)을 세션 속성에 주입
        Map<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put(WatchSubscriptionAttributes.ACTIVE_SUBSCRIPTION_ID_ATTRIBUTE_KEY, 12345);
        StompHeaderAccessor accessor = createAccessor("sub-1", sessionAttributes);

        // when
        String result = WatchSubscriptionAttributes.currentActiveSubscriptionId(accessor);

        // then: ClassCastException이 발생하지 않고 null이 반환되어야 함
        assertThat(result).isNull();
    }
}
