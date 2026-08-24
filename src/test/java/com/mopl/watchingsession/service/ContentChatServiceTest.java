package com.mopl.watchingsession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.content.repository.ContentRepository;
import com.mopl.global.common.UserSummary;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.UserRepository;
import com.mopl.watchingsession.dto.ContentChatDto;
import com.mopl.watchingsession.presence.ContentChatBuffer;
import com.mopl.watchingsession.presence.WatchingSessionPresenceReader;
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
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
public class ContentChatServiceTest {

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private WatchingSessionPresenceReader watchingSessionPresenceReader;

    @Mock
    private ContentChatBuffer contentChatBuffer;

    @InjectMocks
    private ContentChatService contentChatService;

    private static final UUID SENDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private UserSummary createCachedSender() {
        return new UserSummary(SENDER_ID, "우디", "https://example.com/profile.png");
    }
    private User mockUserEntity() {
        User user = User.builder()
            .email("test@example.com")
            .passwordHash("hash")
            .name("우디(DB)")
            .profileImageUrl("https://example.com/profile-db.png")
            .role(UserRole.USER)
            .build();

        ReflectionTestUtils.setField(user, "id", SENDER_ID);
        return user;
    }

    @Test
    @DisplayName("시청 중이고 캐시 히트면 해당 콘텐츠 채팅 destination으로 브로드캐스트")
    void sendAndBroadcast_success_withCacheHit_zeroDbQueries() {
        // given
        UserSummary cachedSender = createCachedSender();
        when(watchingSessionPresenceReader.isWatching(SENDER_ID, CONTENT_ID)).thenReturn(true);
        when(contentRepository.existsById(CONTENT_ID)).thenReturn(true);

        // when
        contentChatService.sendAndBroadcast(SENDER_ID, CONTENT_ID, cachedSender, "안녕하세요");

        // then
        ArgumentCaptor<String> destinationCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ContentChatDto> payloadCaptor = ArgumentCaptor.forClass(ContentChatDto.class);
        verify(messagingTemplate).convertAndSend(destinationCaptor.capture(), payloadCaptor.capture());

        assertThat(destinationCaptor.getValue()).isEqualTo("/sub/contents/" + CONTENT_ID + "/chat");
        assertThat(payloadCaptor.getValue().content()).isEqualTo("안녕하세요");
        assertThat(payloadCaptor.getValue().sender().name()).isEqualTo("우디");

        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("시청 중이나 캐시가 없으면(null) User를 DB에서 조회하여 브로드캐스트")
    void sendAndBroadcast_success_withCacheMiss_fetchesUserFromDb() {
        // given
        when(watchingSessionPresenceReader.isWatching(SENDER_ID, CONTENT_ID)).thenReturn(true);
        when(contentRepository.existsById(CONTENT_ID)).thenReturn(true);
        when(userRepository.findById(SENDER_ID)).thenReturn(Optional.of(mockUserEntity()));

        // when: 캐시된 sender에 null 전달
        contentChatService.sendAndBroadcast(SENDER_ID, CONTENT_ID, null, "안녕하세요");

        // then
        ArgumentCaptor<ContentChatDto> payloadCaptor = ArgumentCaptor.forClass(ContentChatDto.class);
        verify(messagingTemplate).convertAndSend(anyString(), payloadCaptor.capture());

        assertThat(payloadCaptor.getValue().sender().name()).isEqualTo("우디(DB)");

        verify(userRepository).findById(SENDER_ID);
    }

    @Test
    @DisplayName("시청 중이나 존재하지 않는 콘텐츠면 CONTENT_NOT_FOUND 예외를 던지고 브로드캐스트하지 않음")
    void sendAndBroadcast_contentNotFound_throwsAndSkipsBroadcast() {
        // given: presence는 통과하지만 콘텐츠가 논리 삭제된 상황
        when(watchingSessionPresenceReader.isWatching(SENDER_ID, CONTENT_ID)).thenReturn(true);
        when(contentRepository.existsById(CONTENT_ID)).thenReturn(false);

        // when & then
        assertThatThrownBy(() -> contentChatService.sendAndBroadcast(SENDER_ID, CONTENT_ID, createCachedSender(), "안녕하세요"))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONTENT_NOT_FOUND));

        verify(userRepository, never()).findById(any());
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("시청 중이 아니면 콘텐츠 존재 여부와 무관하게 FORBIDDEN 예외를 던짐")
    void sendAndBroadcast_notWatching_throwsForbiddenWithoutContentCheck() {
        // given
        when(watchingSessionPresenceReader.isWatching(SENDER_ID, CONTENT_ID)).thenReturn(false);

        // when & then
        assertThatThrownBy(() -> contentChatService.sendAndBroadcast(SENDER_ID, CONTENT_ID, createCachedSender(), "도배 시도"))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        // presence에서 이미 차단되므로 DB 존재 쿼리 자체가 발생하지 않음
        verify(contentRepository, never()).existsById(any());
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("캐시 미스 상황에서 DB에도 User가 없으면 UNAUTHORIZED 예외를 던진다")
    void sendAndBroadcast_cacheMissAndUserNotFound_throwsUnauthorized() {
        // given
        when(watchingSessionPresenceReader.isWatching(SENDER_ID, CONTENT_ID)).thenReturn(true);
        when(contentRepository.existsById(CONTENT_ID)).thenReturn(true);
        when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty()); // DB에 유저 없음

        // when & then: 캐시 null 전달
        assertThatThrownBy(() -> contentChatService.sendAndBroadcast(SENDER_ID, CONTENT_ID, null, "안녕하세요"))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("브로드캐스트에 성공하면 같은 ContentChatDto로 버퍼에 기록한다")
    void sendAndBroadcast_success_appendsSameDtoToBuffer() {
        // given
        when(watchingSessionPresenceReader.isWatching(SENDER_ID, CONTENT_ID)).thenReturn(true);
        when(contentRepository.existsById(CONTENT_ID)).thenReturn(true);

        // when
        contentChatService.sendAndBroadcast(SENDER_ID, CONTENT_ID, createCachedSender(), "안녕하세요");

        // then
        ArgumentCaptor<ContentChatDto> broadcastCaptor = ArgumentCaptor.forClass(ContentChatDto.class);
        ArgumentCaptor<ContentChatDto> bufferCaptor = ArgumentCaptor.forClass(ContentChatDto.class);
        verify(messagingTemplate).convertAndSend(anyString(), broadcastCaptor.capture());
        verify(contentChatBuffer).append(eq(CONTENT_ID), bufferCaptor.capture());

        assertThat(bufferCaptor.getValue()).isSameAs(broadcastCaptor.getValue());
    }

    @Test
    @DisplayName("시청 중이 아니면 버퍼에 기록하지 않는다")
    void sendAndBroadcast_notWatching_doesNotAppendToBuffer() {
        // given
        when(watchingSessionPresenceReader.isWatching(SENDER_ID, CONTENT_ID)).thenReturn(false);

        // when & then
        assertThatThrownBy(() -> contentChatService.sendAndBroadcast(SENDER_ID, CONTENT_ID, createCachedSender(), "도배 시도"))
            .isInstanceOf(BusinessException.class);

        verifyNoInteractions(contentChatBuffer);
    }

    @Test
    @DisplayName("콘텐츠가 존재하지 않으면 버퍼에 기록하지 않는다")
    void sendAndBroadcast_contentNotFound_doesNotAppendToBuffer() {
        // given
        when(watchingSessionPresenceReader.isWatching(SENDER_ID, CONTENT_ID)).thenReturn(true);
        when(contentRepository.existsById(CONTENT_ID)).thenReturn(false);

        // when & then
        assertThatThrownBy(() -> contentChatService.sendAndBroadcast(SENDER_ID, CONTENT_ID, createCachedSender(), "안녕하세요"))
            .isInstanceOf(BusinessException.class);

        verifyNoInteractions(contentChatBuffer);
    }

    @Test
    @DisplayName("브로드캐스트 자체가 실패하면 버퍼에 기록하지 않는다")
    void sendAndBroadcast_broadcastThrows_doesNotAppendToBuffer() {
        // given
        when(watchingSessionPresenceReader.isWatching(SENDER_ID, CONTENT_ID)).thenReturn(true);
        when(contentRepository.existsById(CONTENT_ID)).thenReturn(true);
        doThrow(new RuntimeException("브로커 전송 실패"))
            .when(messagingTemplate).convertAndSend(anyString(), any(Object.class));

        // when & then
        assertThatThrownBy(() -> contentChatService.sendAndBroadcast(SENDER_ID, CONTENT_ID, createCachedSender(), "안녕하세요"))
            .isInstanceOf(RuntimeException.class);

        verifyNoInteractions(contentChatBuffer);
    }
}
