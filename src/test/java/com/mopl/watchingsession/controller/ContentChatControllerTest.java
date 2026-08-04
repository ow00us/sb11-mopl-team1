package com.mopl.watchingsession.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.watchingsession.dto.ContentChatSendRequest;
import com.mopl.watchingsession.service.ContentChatService;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
public class ContentChatControllerTest {

    @Mock
    private ContentChatService contentChatService;

    @InjectMocks
    private ContentChatController contentChatController;

    private static final UUID SENDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private Principal principalOf(UUID userId) {
        return UsernamePasswordAuthenticationToken.authenticated(userId.toString(), null, java.util.List.of());
    }

    @Test
    @DisplayName("정상 Principal이면 senderId를 추출해 서비스에 위임")
    void sendChat_validPrincipal_delegatesToService() {
        // given
        ContentChatSendRequest request = new ContentChatSendRequest("안녕하세요");

        // when
        contentChatController.sendChat(CONTENT_ID, request, principalOf(SENDER_ID));

        // then
        verify(contentChatService).sendAndBroadcast(SENDER_ID, CONTENT_ID, "안녕하세요");
    }

    @Test
    @DisplayName("Principal이 없으면 UNAUTHORIZED 예외를 던지고 서비스에 위임하지 않음")
    void sendChat_noPrincipal_throwsUnauthorized() {
        // given
        ContentChatSendRequest request = new ContentChatSendRequest("안녕하세요");

        // when & then
        assertThatThrownBy(() -> contentChatController.sendChat(CONTENT_ID, request, null))
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

        // when & then
        assertThatThrownBy(() -> contentChatController.sendChat(CONTENT_ID, request, invalidPrincipal))
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

        // when & then
        assertThatThrownBy(() -> contentChatController.sendChat(CONTENT_ID, request, mockPrincipal))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verifyNoInteractions(contentChatService);
    }
}
