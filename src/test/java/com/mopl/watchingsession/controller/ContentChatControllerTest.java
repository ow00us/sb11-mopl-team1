package com.mopl.watchingsession.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.global.common.UserSummary;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.watchingsession.dto.ContentChatSendRequest;
import com.mopl.watchingsession.service.ContentChatService;
import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
public class ContentChatControllerTest {

    @Mock
    private ContentChatService contentChatService;

    @InjectMocks
    private ContentChatController contentChatController;

    private static final UUID SENDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String CACHE_ATTRIBUTE_KEY = "watchingSession.chatSender";

    private Principal principalOf(UUID userId) {
        return UsernamePasswordAuthenticationToken.authenticated(userId.toString(), null, java.util.List.of());
    }

    // STOMP 세션 속성(캐시)을 포함한 Accessor 생성 헬퍼 메서드
    private SimpMessageHeaderAccessor createAccessorWithCache(UserSummary summary) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create();
        Map<String, Object> attributes = new HashMap<>();
        if (summary != null) {
            attributes.put(CACHE_ATTRIBUTE_KEY, summary);
        }
        accessor.setSessionAttributes(attributes);
        return accessor;
    }

    @Test
    @DisplayName("정상 Principal이고 캐시 히트 시, 추출된 UserSummary를 서비스에 넘김")
    void sendChat_validPrincipalAndCacheHit_delegatesToServiceWithUserSummary() {
        // given
        ContentChatSendRequest request = new ContentChatSendRequest("안녕하세요");
        UserSummary cachedSender = new UserSummary(SENDER_ID, "우디", "url");
        SimpMessageHeaderAccessor accessor = createAccessorWithCache(cachedSender);

        // when
        contentChatController.sendChat(CONTENT_ID, request, principalOf(SENDER_ID), accessor);

        // then
        verify(contentChatService).sendAndBroadcast(eq(SENDER_ID), eq(CONTENT_ID), eq(cachedSender), eq("안녕하세요"));
    }

    @Test
    @DisplayName("정상 Principal이지만 캐시 미스 시(null), 서비스에 null을 넘겨 폴백 조회를 위임")
    void sendChat_validPrincipalAndCacheMiss_delegatesToServiceWithNull() {
        // given
        ContentChatSendRequest request = new ContentChatSendRequest("안녕하세요");
        // 캐시가 없는 빈 속성의 Accessor 생성
        SimpMessageHeaderAccessor accessor = createAccessorWithCache(null);

        // when
        contentChatController.sendChat(CONTENT_ID, request, principalOf(SENDER_ID), accessor);

        // then
        // 캐시가 없으므로 sender 자리에 null이 넘어가야 함
        verify(contentChatService).sendAndBroadcast(eq(SENDER_ID), eq(CONTENT_ID), isNull(), eq("안녕하세요"));
    }

    @Test
    @DisplayName("Principal이 없으면 UNAUTHORIZED 예외를 던지고 서비스에 위임하지 않음")
    void sendChat_noPrincipal_throwsUnauthorized() {
        // given
        ContentChatSendRequest request = new ContentChatSendRequest("안녕하세요");
        SimpMessageHeaderAccessor accessor = createAccessorWithCache(null);

        // when & then
        assertThatThrownBy(() -> contentChatController.sendChat(CONTENT_ID, request, null, accessor))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verifyNoInteractions(contentChatService);
    }

    @Test
    @DisplayName("Principal 이름이 UUID 형식이 아니면 UNAUTHORIZED 예외를 던지고 서비스에 위임하지 않음")
    void sendChat_invalidPrincipalName_throwsUnauthorized() {
        // given
        ContentChatSendRequest request = new ContentChatSendRequest("안녕하세요");
        Principal invalidPrincipal = UsernamePasswordAuthenticationToken
            .authenticated("invalid-user-id", null, List.of());
        SimpMessageHeaderAccessor accessor = createAccessorWithCache(null);

        // when & then
        assertThatThrownBy(() -> contentChatController.sendChat(CONTENT_ID, request, invalidPrincipal, accessor))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verifyNoInteractions(contentChatService);
    }

    @Test
    @DisplayName("Principal 객체는 있으나 Name(식별자)이 null이면 UNAUTHORIZED 예외를 던짐")
    void sendChat_principalNameIsNull_throwsUnauthorized() {
        // given
        ContentChatSendRequest request = new ContentChatSendRequest("안녕하세요");
        Principal mockPrincipal = mock(Principal.class);
        when(mockPrincipal.getName()).thenReturn(null);

        SimpMessageHeaderAccessor accessor = createAccessorWithCache(null);

        // when & then
        assertThatThrownBy(() -> contentChatController.sendChat(CONTENT_ID, request, mockPrincipal, accessor))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verifyNoInteractions(contentChatService);
    }
}
