package com.mopl.watchingsession.websocket;

import static org.assertj.core.api.Assertions.assertThat;

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
    @DisplayName("저장한 매핑을 remove로 꺼내면 저장했던 contentId가 반환됨")
    void remove_success_returnsStoredContentId() {
        Map<String, Object> sessionAttributes = new HashMap<>();
        StompHeaderAccessor putAccessor = createAccessor("sub-1", sessionAttributes);
        WatchSubscriptionAttributes.put(putAccessor, CONTENT_ID);

        StompHeaderAccessor removeAccessor = createAccessor("sub-1", sessionAttributes);
        UUID result = WatchSubscriptionAttributes.remove(removeAccessor);

        assertThat(result).isEqualTo(CONTENT_ID);
    }

    @Test
    @DisplayName("매핑되지 않은 subscriptionId를 remove하면 null 반환")
    void remove_returnsNull_whenNoMappingExists() {
        StompHeaderAccessor accessor = createAccessor("sub-unknown", new HashMap<>());

        UUID result = WatchSubscriptionAttributes.remove(accessor);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("subscriptionId가 없으면 remove는 null 반환")
    void remove_returnsNull_whenSubscriptionIdIsNull() {
        StompHeaderAccessor accessor = createAccessor(null, new HashMap<>());

        UUID result = WatchSubscriptionAttributes.remove(accessor);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("sessionAttributes가 없으면 remove는 null 반환")
    void remove_returnsNull_whenSessionAttributesIsNull() {
        StompHeaderAccessor accessor = createAccessor("sub-1", null);

        UUID result = WatchSubscriptionAttributes.remove(accessor);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("같은 subscriptionId를 두 번 remove하면 두 번째는 null 반환 (소비 후 재조회 불가)")
    void remove_returnsNull_whenCalledTwice() {
        Map<String, Object> sessionAttributes = new HashMap<>();
        StompHeaderAccessor putAccessor = createAccessor("sub-1", sessionAttributes);
        WatchSubscriptionAttributes.put(putAccessor, CONTENT_ID);

        StompHeaderAccessor firstRemoveAccessor = createAccessor("sub-1", sessionAttributes);
        StompHeaderAccessor secondRemoveAccessor = createAccessor("sub-1", sessionAttributes);

        UUID first = WatchSubscriptionAttributes.remove(firstRemoveAccessor);
        UUID second = WatchSubscriptionAttributes.remove(secondRemoveAccessor);

        assertThat(first).isEqualTo(CONTENT_ID);
        assertThat(second).isNull();
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
    @DisplayName("remove 이후에도 activeSubscriptionId 기록 자체는 남아있어 isActive는 여전히 true")
    void isActive_stillTrue_afterRemove_becauseRemoveOnlyClearsMapping() {
        // remove()는 subscriptionMap의 항목만 지우고 activeSubscriptionId는 건드리지 않는다.
        // UNSUBSCRIBE 리스너가 isActive()를 remove() 이전에 평가하므로
        // 실제 흐름에서는 이 케이스가 문제되지 않음
        Map<String, Object> sessionAttributes = new HashMap<>();
        StompHeaderAccessor putAccessor = createAccessor("sub-1", sessionAttributes);
        WatchSubscriptionAttributes.put(putAccessor, CONTENT_ID);

        StompHeaderAccessor removeAccessor = createAccessor("sub-1", sessionAttributes);
        WatchSubscriptionAttributes.remove(removeAccessor);

        StompHeaderAccessor checkAccessor = createAccessor("sub-1", sessionAttributes);
        assertThat(WatchSubscriptionAttributes.isActive(checkAccessor)).isTrue();
    }
}
