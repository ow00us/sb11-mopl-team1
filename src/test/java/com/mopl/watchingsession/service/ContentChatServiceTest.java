package com.mopl.watchingsession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.content.repository.ContentRepository;
import com.mopl.global.common.UserSummary;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.UserRepository;
import com.mopl.watchingsession.dto.ContentChatDto;
import com.mopl.watchingsession.entity.WatchingSessionSnapshot;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private WatchingSessionSnapshotRepository watchingSessionSnapshotRepository;

    @InjectMocks
    private ContentChatService contentChatService;

    private static final UUID SENDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_CONTENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private  WatchingSessionSnapshot watchingSessionSnapshot(UUID contentId, Instant expiresAt) {
        WatchingSessionSnapshot snapshot = WatchingSessionSnapshot.builder()
            .watcherId(SENDER_ID)
            .contentId(contentId)
            .expiresAt(expiresAt)
            .build();
        ReflectionTestUtils.setField(snapshot, "id", UUID.randomUUID());
        return snapshot;
    }

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
        WatchingSessionSnapshot validSnapshot = watchingSessionSnapshot(CONTENT_ID, Instant.now().plus(1, ChronoUnit.HOURS));

        when(contentRepository.existsById(CONTENT_ID)).thenReturn(true);
        when(watchingSessionSnapshotRepository.findByWatcherId(SENDER_ID)).thenReturn(Optional.of(validSnapshot));

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
        WatchingSessionSnapshot validSnapshot = watchingSessionSnapshot(CONTENT_ID, Instant.now().plus(1, ChronoUnit.HOURS));

        when(contentRepository.existsById(CONTENT_ID)).thenReturn(true);
        when(watchingSessionSnapshotRepository.findByWatcherId(SENDER_ID)).thenReturn(Optional.of(validSnapshot));
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
    @DisplayName("존재하지 않는 콘텐츠면 CONTENT_NOT_FOUND 예외를 던지고 브로드캐스트하지 않음")
    void sendAndBroadcast_contentNotFound_throwsAndSkipsBroadcast() {
        // given
        when(contentRepository.existsById(CONTENT_ID)).thenReturn(false);

        // when & then
        assertThatThrownBy(() -> contentChatService.sendAndBroadcast(SENDER_ID, CONTENT_ID, createCachedSender(), "안녕하세요"))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONTENT_NOT_FOUND));

        verify(watchingSessionSnapshotRepository, never()).findByWatcherId(any());
        verify(userRepository, never()).findById(any());
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("다른 콘텐츠를 시청 중이면 FORBIDDEN 예외를 던짐")
    void sendAndBroadcast_watchingOtherContent_throwsForbidden() {
        // given: 다른 콘텐츠 시청 중
        WatchingSessionSnapshot snapshotOther = watchingSessionSnapshot(OTHER_CONTENT_ID, Instant.now().plus(1, ChronoUnit.HOURS));

        when(contentRepository.existsById(CONTENT_ID)).thenReturn(true);
        when(watchingSessionSnapshotRepository.findByWatcherId(SENDER_ID)).thenReturn(Optional.of(snapshotOther));

        // when & then
        assertThatThrownBy(() -> contentChatService.sendAndBroadcast(SENDER_ID, CONTENT_ID, createCachedSender(), "도배 시도"))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("시청 세션이 만료되었으면 FORBIDDEN 예외를 던짐")
    void sendAndBroadcast_sessionExpired_throwsForbidden() {
        // given: 해당 콘텐츠를 시청했으나 만료됨
        WatchingSessionSnapshot expiredSnapshot = watchingSessionSnapshot(CONTENT_ID, Instant.now().minus(1, ChronoUnit.HOURS));

        when(contentRepository.existsById(CONTENT_ID)).thenReturn(true);
        when(watchingSessionSnapshotRepository.findByWatcherId(SENDER_ID)).thenReturn(Optional.of(expiredSnapshot));

        // when & then
        assertThatThrownBy(() -> contentChatService.sendAndBroadcast(SENDER_ID, CONTENT_ID, createCachedSender(), "안녕하세요"))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("캐시 미스 상황에서 DB에도 User가 없으면 UNAUTHORIZED 예외를 던진다")
    void sendAndBroadcast_cacheMissAndUserNotFound_throwsUnauthorized() {
        // given
        WatchingSessionSnapshot validSnapshot = watchingSessionSnapshot(CONTENT_ID, Instant.now().plus(1, ChronoUnit.HOURS));

        when(contentRepository.existsById(CONTENT_ID)).thenReturn(true);
        when(watchingSessionSnapshotRepository.findByWatcherId(SENDER_ID)).thenReturn(Optional.of(validSnapshot));
        when(userRepository.findById(SENDER_ID)).thenReturn(Optional.empty()); // DB에 유저 없음

        // when & then: 캐시 null 전달
        assertThatThrownBy(() -> contentChatService.sendAndBroadcast(SENDER_ID, CONTENT_ID, null, "안녕하세요"))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }
}
