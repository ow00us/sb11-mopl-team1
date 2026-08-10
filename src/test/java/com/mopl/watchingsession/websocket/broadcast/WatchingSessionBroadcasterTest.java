package com.mopl.watchingsession.websocket.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.global.common.ContentSummary;
import com.mopl.global.common.UserSummary;
import com.mopl.watchingsession.dto.WatchingSessionChange;
import com.mopl.watchingsession.dto.WatchingSessionDto;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import java.time.Instant;
import java.util.List;
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
public class WatchingSessionBroadcasterTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private WatchingSessionSnapshotRepository watchingSessionSnapshotRepository;

    @InjectMocks
    private WatchingSessionBroadcaster broadcaster;

    private static final UUID WATCHER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private WatchingSessionDto dtoFixture() {
        return new WatchingSessionDto(
            UUID.randomUUID(),
            new UserSummary(WATCHER_ID, "테스트유저", null),
            new ContentSummary(CONTENT_ID, "movie", "테스트콘텐츠", "설명", null, List.of(), 0.0, 0),
            Instant.now()
        );
    }

    @Test
    @DisplayName("메시지 전송이 실패해도 예외를 호출자로 전파하지 않음")
    void broadcastJoin_doesNotThrow_whenSendFails() {
        // given
        when(watchingSessionSnapshotRepository.countByContentId(eq(CONTENT_ID), any(), any()))
            .thenReturn(3L);
        doThrow(new RuntimeException("브로커 전송 실패"))
            .when(messagingTemplate).convertAndSend(anyString(), any((Object.class)));

        // when: 예외 없이 끝나야 함
        broadcaster.broadcastJoin(dtoFixture(), CONTENT_ID);

        // then: 전송 시도 자체는 실제로 있었는지 확인
        verify(messagingTemplate).convertAndSend(eq("/sub/contents/" + CONTENT_ID + "/watch"), any(Object.class));

    }

    @Test
    @DisplayName("시청자 수 조회가 실패하면 대체 값(-1)으로 브로드캐스트가 시도됨")
    void broadcastLeave_stillSends_whenWatcherCountQueryFails() {
        // given
        when(watchingSessionSnapshotRepository.countByContentId(eq(CONTENT_ID), any(), any()))
            .thenThrow(new RuntimeException("DB 커넥션 끊김"));

        // when
        broadcaster.broadcastLeave(dtoFixture(), CONTENT_ID);

        // then
        ArgumentCaptor<WatchingSessionChange> payloadCaptor = ArgumentCaptor.forClass(WatchingSessionChange.class);
        verify(messagingTemplate).convertAndSend(eq("/sub/contents/" + CONTENT_ID + "/watch"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().watcherCount()).isEqualTo(-1L);
    }

    @Test
    @DisplayName("정상 경로에서는 조회한 시청자 수 그대로 전송")
    void broadcastJoin_success_sendsActualWatcherCount() {
        // given
        when(watchingSessionSnapshotRepository.countByContentId(eq(CONTENT_ID), any(), any()))
            .thenReturn(5L);

        // when
        broadcaster.broadcastJoin(dtoFixture(), CONTENT_ID);

        // then
        ArgumentCaptor<WatchingSessionChange> payloadCaptor = ArgumentCaptor.forClass(WatchingSessionChange.class);
        verify(messagingTemplate, times(1))
            .convertAndSend(eq("/sub/contents/" + CONTENT_ID + "/watch"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().watcherCount()).isEqualTo(5L);
    }
}
