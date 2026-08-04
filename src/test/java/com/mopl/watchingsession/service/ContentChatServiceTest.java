package com.mopl.watchingsession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.content.repository.ContentRepository;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.entity.User;
import com.mopl.user.repository.UserRepository;
import com.mopl.watchingsession.dto.ContentChatDto;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@ExtendWith(MockitoExtension.class)
public class ContentChatServiceTest {

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private ContentChatService contentChatService;

    private static final UUID SENDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private User mockSender() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(SENDER_ID);
        when(user.getName()).thenReturn("우디");
        when(user.getProfileImageUrl()).thenReturn("https://example.com/profile.png");
        return user;
    }

    @Test
    @DisplayName("정상 요청이면 해당 콘텐츠 채팅 destination으로 브로드캐스트")
    void sendAndBroadcast_success_broadcastsToCorrectDestination() {
        // given
        when(contentRepository.existsById(CONTENT_ID)).thenReturn(true);
        User sender = mockSender();
        when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(sender));

        // when
        contentChatService.sendAndBroadcast(SENDER_ID, CONTENT_ID, "안녕하세요");

        // then
        ArgumentCaptor<String> destinationCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ContentChatDto> payloadCaptor = ArgumentCaptor.forClass(ContentChatDto.class);
        verify(messagingTemplate).convertAndSend(destinationCaptor.capture(), payloadCaptor.capture());

        assertThat(destinationCaptor.getValue()).isEqualTo("/sub/contents/" + CONTENT_ID + "/chat");
        assertThat(payloadCaptor.getValue().content()).isEqualTo("안녕하세요");
        assertThat(payloadCaptor.getValue().sender().userId()).isEqualTo(SENDER_ID);
    }

    @Test
    @DisplayName("존재하지 않는 콘텐츠면 CONTENT_NOT_FOUND 예외를 던지고 브로드캐스트하지 않음")
    void sendAndBroadcast_contentNotFound_throwsAndSkipsBroadcast() {
        // given
        when(contentRepository.existsById(CONTENT_ID)).thenReturn(false);

        // when & then
        assertThatThrownBy(() -> contentChatService.sendAndBroadcast(SENDER_ID, CONTENT_ID, "안녕하세요"))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONTENT_NOT_FOUND));

        verify(userRepository, never()).findById(any());
        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    @DisplayName("인증은 통과했으나 DB에 존재하지 않는 발신자면 UNAUTHORIZED 예외를 던지고 브로드캐스트하지 않음")
    void sendAndBroadcast_userNotFound_throwsAndSkipsBroadcast() {
        // given
        when(contentRepository.existsById(CONTENT_ID)).thenReturn(true);
        when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> contentChatService.sendAndBroadcast(SENDER_ID, CONTENT_ID, "안녕하세요"))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }





}
