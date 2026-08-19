package com.mopl.playlist.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.outbox.OutboxRecorder;
import com.mopl.global.util.CursorUtils;
import com.mopl.playlist.dto.PlaylistCreateRequest;
import com.mopl.playlist.dto.PlaylistDto;
import com.mopl.playlist.dto.PlaylistUpdateRequest;
import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentType;
import com.mopl.playlist.entity.Playlist;
import com.mopl.playlist.entity.PlaylistContent;
import com.mopl.playlist.entity.PlaylistSubscription;
import com.mopl.playlist.event.PlaylistSubscriptionEventFactory;
import com.mopl.content.repository.ContentRepository;
import com.mopl.playlist.repository.PlaylistContentRepository;
import com.mopl.playlist.repository.PlaylistRepository;
import com.mopl.playlist.repository.PlaylistSubscriptionRepository;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaylistServiceTest {

    @Mock PlaylistRepository playlistRepository;
    @Mock PlaylistSubscriptionRepository subscriptionRepository;
    @Mock PlaylistContentRepository playlistContentRepository;
    @Mock ContentRepository contentRepository;
    @Mock PlaylistContentSaver playlistContentSaver;
    @Mock UserRepository userRepository;
    @Mock OutboxRecorder outboxRecorder;
    @Spy PlaylistSubscriptionEventFactory playlistSubscriptionEventFactory =
            new PlaylistSubscriptionEventFactory(new ObjectMapper());

    @InjectMocks
    PlaylistServiceImpl playlistService;

    private static final UUID OWNER_ID    = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_ID    = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID PLAYLIST_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        lenient().when(playlistContentRepository.findAllByPlaylistIdOrderByCreatedAtAsc(any()))
                .thenReturn(List.of());
    }

    // ── create ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("플레이리스트 생성 시 저장 후 PlaylistDto 를 반환한다")
    void create_success() {
        PlaylistCreateRequest request = new PlaylistCreateRequest("내 플레이리스트", "설명");
        Playlist saved = savedPlaylist(PLAYLIST_ID, OWNER_ID, "내 플레이리스트", "설명", Instant.now());

        when(playlistRepository.save(any(Playlist.class))).thenReturn(saved);

        PlaylistDto result = playlistService.create(request, OWNER_ID);

        assertThat(result.title()).isEqualTo("내 플레이리스트");
        assertThat(result.description()).isEqualTo("설명");
        assertThat(result.owner().userId()).isEqualTo(OWNER_ID);
        verify(playlistRepository).save(any(Playlist.class));
    }

    // ── get ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("존재하는 플레이리스트 단건 조회 시 PlaylistDto 를 반환한다")
    void get_success() {
        Playlist playlist = savedPlaylist(PLAYLIST_ID, OWNER_ID, "제목", "설명", Instant.now());
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));
        when(subscriptionRepository.existsByPlaylistIdAndSubscriberId(PLAYLIST_ID, OTHER_ID)).thenReturn(true);

        PlaylistDto result = playlistService.get(PLAYLIST_ID, OTHER_ID);

        assertThat(result.id()).isEqualTo(PLAYLIST_ID);
        assertThat(result.title()).isEqualTo("제목");
        assertThat(result.subscribedByMe()).isTrue();
    }

    @Test
    @DisplayName("requesterId 가 null 이면 subscribedByMe 는 false 를 반환한다")
    void get_success_anonymous() {
        Playlist playlist = savedPlaylist(PLAYLIST_ID, OWNER_ID, "제목", "설명", Instant.now());
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));

        PlaylistDto result = playlistService.get(PLAYLIST_ID, null);

        assertThat(result.subscribedByMe()).isFalse();
        verifyNoInteractions(subscriptionRepository);
    }

    @Test
    @DisplayName("존재하지 않는 ID 조회 시 RESOURCE_NOT_FOUND 예외가 발생한다")
    void get_fail_notFound() {
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> playlistService.get(PLAYLIST_ID, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ── getList ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("첫 페이지 목록 조회 시 hasNext false 와 데이터를 반환한다")
    void getList_firstPage_noNextPage() {
        List<Playlist> rows = List.of(
                savedPlaylist(UUID.randomUUID(), OWNER_ID, "A", "a", Instant.now()),
                savedPlaylist(UUID.randomUUID(), OWNER_ID, "B", "b", Instant.now())
        );
        when(playlistRepository.findByUpdatedAtAsc(null, null, null, null, null, 3)).thenReturn(rows);
        when(playlistRepository.countByFilter(null, null, null)).thenReturn(2L);

        CursorResponse<PlaylistDto> result = playlistService.getList(
                null, null, null, null, null, 2, "updatedAt", "ASCENDING", null);

        assertThat(result.data()).hasSize(2);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    @DisplayName("getList는 페이지 크기와 무관하게 콘텐츠·태그 조회를 상수 쿼리(playlist_contents 1 + contents+tags 1)로 수행한다")
    void getList_batchLoadsContents_noNPlusOne() {
        Playlist p1 = savedPlaylist(UUID.randomUUID(), OWNER_ID, "A", "a", Instant.now());
        Playlist p2 = savedPlaylist(UUID.randomUUID(), OWNER_ID, "B", "b", Instant.now());
        Playlist p3 = savedPlaylist(UUID.randomUUID(), OWNER_ID, "C", "c", Instant.now());
        List<Playlist> rows = List.of(p1, p2, p3);

        UUID contentId1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID contentId2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID contentId3 = UUID.fromString("33333333-3333-3333-3333-333333333333");

        Content content1 = savedContent(contentId1, "콘텐츠1");
        Content content2 = savedContent(contentId2, "콘텐츠2");
        Content content3 = savedContent(contentId3, "콘텐츠3");

        List<PlaylistContent> links = List.of(
                savedLink(p1.getId(), contentId1, Instant.now().minusSeconds(30)),
                savedLink(p1.getId(), contentId2, Instant.now().minusSeconds(20)),
                savedLink(p2.getId(), contentId3, Instant.now().minusSeconds(10))
        );

        when(playlistRepository.findByUpdatedAtAsc(null, null, null, null, null, 4)).thenReturn(rows);
        when(playlistRepository.countByFilter(null, null, null)).thenReturn(3L);
        when(playlistContentRepository.findAllByPlaylistIdInOrderByPlaylistIdAscCreatedAtAsc(anyList()))
                .thenReturn(links);
        when(contentRepository.findAllWithTagsByIdIn(anyList()))
                .thenReturn(List.of(content1, content2, content3));

        CursorResponse<PlaylistDto> result = playlistService.getList(
                null, null, null, null, null, 3, "updatedAt", "ASCENDING", null);

        // 배치 쿼리 계약: 콘텐츠 연결 1회 + 콘텐츠(+태그) 1회
        verify(playlistContentRepository, times(1))
                .findAllByPlaylistIdInOrderByPlaylistIdAscCreatedAtAsc(anyList());
        verify(contentRepository, times(1)).findAllWithTagsByIdIn(anyList());
        // 항목별 조회 및 태그 lazy 로딩 유발 경로 미사용 검증
        verify(playlistContentRepository, never())
                .findAllByPlaylistIdOrderByCreatedAtAsc(any(UUID.class));
        verify(contentRepository, never()).findAllById(any());

        // 순서·매핑 검증: p1 → [content1, content2], p2 → [content3], p3 → []
        assertThat(result.data()).hasSize(3);
        assertThat(result.data().get(0).contents()).extracting("id")
                .containsExactly(contentId1, contentId2);
        assertThat(result.data().get(1).contents()).extracting("id")
                .containsExactly(contentId3);
        assertThat(result.data().get(2).contents()).isEmpty();
    }

    @Test
    @DisplayName("다음 페이지가 있으면 hasNext true 와 nextCursor 를 반환한다")
    void getList_hasNextPage() {
        Instant now = Instant.now();
        List<Playlist> rows = List.of(
                savedPlaylist(UUID.randomUUID(), OWNER_ID, "A", "a", now),
                savedPlaylist(UUID.randomUUID(), OWNER_ID, "B", "b", now),
                savedPlaylist(UUID.randomUUID(), OWNER_ID, "C", "c", now)
        );
        when(playlistRepository.findByUpdatedAtAsc(null, null, null, null, null, 3)).thenReturn(rows);
        when(playlistRepository.countByFilter(null, null, null)).thenReturn(5L);

        CursorResponse<PlaylistDto> result = playlistService.getList(
                null, null, null, null, null, 2, "updatedAt", "ASCENDING", null);

        assertThat(result.data()).hasSize(2);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.nextCursor()).isNotNull();
        assertThat(result.nextIdAfter()).isNotNull();
    }

    @Test
    @DisplayName("ownerIdEqual 필터 적용 시 total 이 필터된 개수를 반환한다")
    void getList_filterByOwner_returnsFilteredTotal() {
        List<Playlist> rows = List.of(
                savedPlaylist(UUID.randomUUID(), OWNER_ID, "A", "a", Instant.now())
        );
        when(playlistRepository.findByUpdatedAtAsc(null, OWNER_ID.toString(), null, null, null, 2))
                .thenReturn(rows);
        when(playlistRepository.countByFilter(null, OWNER_ID.toString(), null)).thenReturn(1L);

        CursorResponse<PlaylistDto> result = playlistService.getList(
                null, OWNER_ID, null, null, null, 1, "updatedAt", "ASCENDING", null);

        assertThat(result.data()).hasSize(1);
        assertThat(result.totalCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("subscriberIdEqual 필터 적용 시 total 이 필터된 개수를 반환한다")
    void getList_filterBySubscriber_returnsFilteredTotal() {
        List<Playlist> rows = List.of(
                savedPlaylist(UUID.randomUUID(), OWNER_ID, "A", "a", Instant.now())
        );
        when(playlistRepository.findByUpdatedAtAsc(null, null, OTHER_ID.toString(), null, null, 2))
                .thenReturn(rows);
        when(playlistRepository.countByFilter(null, null, OTHER_ID.toString())).thenReturn(1L);

        CursorResponse<PlaylistDto> result = playlistService.getList(
                null, null, OTHER_ID, null, null, 1, "updatedAt", "ASCENDING", null);

        assertThat(result.data()).hasSize(1);
        assertThat(result.totalCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("잘못된 cursor 값이 들어오면 INVALID_INPUT 예외가 발생한다")
    void getList_fail_invalidCursor() {
        assertThatThrownBy(() -> playlistService.getList(
                null, null, null, "invalid-cursor!!", null, 10, "updatedAt", "ASCENDING", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("cursor 가 base64 는 유효하지만 디코딩 결과가 ISO-8601 이 아니면 INVALID_INPUT 예외가 발생한다")
    void getList_fail_cursorNotIso8601() {
        // base64 로는 디코드에 성공하지만 Instant.parse 가 DateTimeParseException 을
        // 던지는 커서. fetchPage 의 catch 절이 IllegalArgumentException 만 잡을 때는
        // GlobalExceptionHandler catch-all 로 500 이 되었으므로 회귀 방지용으로 추가.
        String base64ValidButNotIso = CursorUtils.encode("not-an-iso-instant");

        assertThatThrownBy(() -> playlistService.getList(
                null, null, null, base64ValidButNotIso, UUID.randomUUID(),
                10, "updatedAt", "ASCENDING", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("cursor만 있고 idAfter가 없으면 INVALID_INPUT 예외가 발생한다")
    void getList_fail_cursorWithoutIdAfter() {
        String validCursor = CursorUtils.encodeInstant(Instant.now());

        assertThatThrownBy(() -> playlistService.getList(
                null, null, null, validCursor, null, 10, "updatedAt", "ASCENDING", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("idAfter만 있고 cursor가 없으면 INVALID_INPUT 예외가 발생한다")
    void getList_fail_idAfterWithoutCursor() {
        assertThatThrownBy(() -> playlistService.getList(
                null, null, null, null, UUID.randomUUID(), 10, "updatedAt", "ASCENDING", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    // ── update ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("소유자가 수정 시 saveAndFlush 를 거쳐 갱신된 updatedAt 의 PlaylistDto 를 반환한다")
    void update_success() {
        Instant newUpdatedAt = Instant.now();
        Playlist playlist = savedPlaylist(PLAYLIST_ID, OWNER_ID, "원래 제목", "원래 설명", newUpdatedAt.minusSeconds(60));
        Playlist flushed  = savedPlaylist(PLAYLIST_ID, OWNER_ID, "새 제목",   "원래 설명", newUpdatedAt);
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));
        when(playlistRepository.saveAndFlush(any(Playlist.class))).thenReturn(flushed);

        PlaylistDto result = playlistService.update(
                PLAYLIST_ID, new PlaylistUpdateRequest("새 제목", null), OWNER_ID);

        assertThat(result.title()).isEqualTo("새 제목");
        assertThat(result.description()).isEqualTo("원래 설명");
        assertThat(result.updatedAt()).isEqualTo(newUpdatedAt);
        verify(playlistRepository).saveAndFlush(any(Playlist.class));
    }

    @Test
    @DisplayName("update 응답 contents 는 저장된 콘텐츠 목록으로 채워져 반환된다")
    void update_returnsExistingContents() {
        Instant newUpdatedAt = Instant.now();
        Playlist playlist = savedPlaylist(PLAYLIST_ID, OWNER_ID, "원 제목", "설명", newUpdatedAt.minusSeconds(60));
        Playlist flushed  = savedPlaylist(PLAYLIST_ID, OWNER_ID, "새 제목", "설명", newUpdatedAt);
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));
        when(playlistRepository.saveAndFlush(any(Playlist.class))).thenReturn(flushed);

        UUID contentId = UUID.randomUUID();
        when(playlistContentRepository.findAllByPlaylistIdOrderByCreatedAtAsc(PLAYLIST_ID))
                .thenReturn(List.of(savedLink(PLAYLIST_ID, contentId, Instant.now())));
        when(contentRepository.findAllWithTagsByIdIn(List.of(contentId)))
                .thenReturn(List.of(savedContent(contentId, "콘텐츠 A")));

        PlaylistDto result = playlistService.update(
                PLAYLIST_ID, new PlaylistUpdateRequest("새 제목", null), OWNER_ID);

        assertThat(result.contents()).hasSize(1);
        assertThat(result.contents().get(0).id()).isEqualTo(contentId);
        assertThat(result.contents().get(0).title()).isEqualTo("콘텐츠 A");
    }

    @Test
    @DisplayName("소유자가 아닌 사용자가 수정하면 FORBIDDEN 예외가 발생한다")
    void update_fail_forbidden() {
        Playlist playlist = savedPlaylist(PLAYLIST_ID, OWNER_ID, "제목", "설명", Instant.now());
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));

        assertThatThrownBy(() -> playlistService.update(
                PLAYLIST_ID, new PlaylistUpdateRequest("새 제목", null), OTHER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("존재하지 않는 플레이리스트 수정 시 RESOURCE_NOT_FOUND 예외가 발생한다")
    void update_fail_notFound() {
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> playlistService.update(
                PLAYLIST_ID, new PlaylistUpdateRequest("제목", null), OWNER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ── delete ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("소유자가 삭제 시 정상 삭제된다")
    void delete_success() {
        Playlist playlist = savedPlaylist(PLAYLIST_ID, OWNER_ID, "제목", "설명", Instant.now());
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));

        playlistService.delete(PLAYLIST_ID, OWNER_ID);

        verify(playlistRepository).delete(playlist);
    }

    @Test
    @DisplayName("소유자가 아닌 사용자가 삭제하면 FORBIDDEN 예외가 발생한다")
    void delete_fail_forbidden() {
        Playlist playlist = savedPlaylist(PLAYLIST_ID, OWNER_ID, "제목", "설명", Instant.now());
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));

        assertThatThrownBy(() -> playlistService.delete(PLAYLIST_ID, OTHER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);

        verify(playlistRepository, never()).delete(any());
    }

    @Test
    @DisplayName("존재하지 않는 플레이리스트 삭제 시 RESOURCE_NOT_FOUND 예외가 발생한다")
    void delete_fail_notFound() {
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> playlistService.delete(PLAYLIST_ID, OWNER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ── subscribe ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("구독 성공 시 upsert rows=1 이면 subscriberCount 를 증가시킨다")
    void subscribe_success() {
        UUID subscriptionId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        Playlist playlist = savedPlaylist(PLAYLIST_ID, OWNER_ID, "제목", "설명", Instant.now());
        PlaylistSubscription subscription = savedSubscriptionWithCreatedAt(
                subscriptionId, PLAYLIST_ID, OTHER_ID, Instant.now());
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));
        when(subscriptionRepository.insertIfAbsent(PLAYLIST_ID.toString(), OTHER_ID.toString()))
                .thenReturn(1);
        when(subscriptionRepository.findByPlaylistIdAndSubscriberId(PLAYLIST_ID, OTHER_ID))
                .thenReturn(Optional.of(subscription));

        playlistService.subscribe(PLAYLIST_ID, OTHER_ID);

        verify(subscriptionRepository).insertIfAbsent(PLAYLIST_ID.toString(), OTHER_ID.toString());
        verify(playlistRepository).incrementSubscriberCount(PLAYLIST_ID);
    }

    @Test
    @DisplayName("소유자가 본인 플레이리스트를 구독하면 FORBIDDEN 예외가 발생한다")
    void subscribe_fail_owner() {
        Playlist playlist = savedPlaylist(PLAYLIST_ID, OWNER_ID, "제목", "설명", Instant.now());
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));

        assertThatThrownBy(() -> playlistService.subscribe(PLAYLIST_ID, OWNER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);

        verifyNoInteractions(subscriptionRepository);
    }

    @Test
    @DisplayName("중복 구독 시 upsert rows=0, subscriberCount 는 재증가하지 않는다 (ADR 2)")
    void subscribe_duplicate_noOp() {
        Playlist playlist = savedPlaylist(PLAYLIST_ID, OWNER_ID, "제목", "설명", Instant.now());
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));
        when(subscriptionRepository.insertIfAbsent(PLAYLIST_ID.toString(), OTHER_ID.toString()))
                .thenReturn(0);

        playlistService.subscribe(PLAYLIST_ID, OTHER_ID);

        verify(playlistRepository, never()).incrementSubscriberCount(any(UUID.class));
    }

    // 계약 docs/07-kafka-outbox-contract.md §8.2: playlist.subscription.created 는
    // PlaylistServiceImpl.subscribe() 가 INSERT 성공(rows=1) 으로 판정한 경우에만 Outbox 기록한다.
    // 여기서는 호출 여부만 검증하고 envelope 필드 정확성은 후속 Envelope 커밋에서 다룬다.

    @Test
    @DisplayName("구독 신규 판정 시 OutboxRecorder.record 를 1회 호출한다")
    void subscribe_success_recordsOutboxOnce() {
        UUID subscriptionId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        Playlist playlist = savedPlaylist(PLAYLIST_ID, OWNER_ID, "제목", "설명", Instant.now());
        PlaylistSubscription subscription = savedSubscriptionWithCreatedAt(
                subscriptionId, PLAYLIST_ID, OTHER_ID, Instant.now());
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));
        when(subscriptionRepository.insertIfAbsent(PLAYLIST_ID.toString(), OTHER_ID.toString()))
                .thenReturn(1);
        when(subscriptionRepository.findByPlaylistIdAndSubscriberId(PLAYLIST_ID, OTHER_ID))
                .thenReturn(Optional.of(subscription));

        playlistService.subscribe(PLAYLIST_ID, OTHER_ID);

        verify(outboxRecorder, times(1)).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName("중복 구독 판정 시 OutboxRecorder.record 를 호출하지 않는다")
    void subscribe_duplicate_doesNotRecordOutbox() {
        Playlist playlist = savedPlaylist(PLAYLIST_ID, OWNER_ID, "제목", "설명", Instant.now());
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));
        when(subscriptionRepository.insertIfAbsent(PLAYLIST_ID.toString(), OTHER_ID.toString()))
                .thenReturn(0);

        playlistService.subscribe(PLAYLIST_ID, OTHER_ID);

        verify(outboxRecorder, never()).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName("자기 자신 플레이리스트 구독 차단 시 OutboxRecorder.record 를 호출하지 않는다")
    void subscribe_fail_owner_doesNotRecordOutbox() {
        Playlist playlist = savedPlaylist(PLAYLIST_ID, OWNER_ID, "제목", "설명", Instant.now());
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));

        assertThatThrownBy(() -> playlistService.subscribe(PLAYLIST_ID, OWNER_ID))
                .isInstanceOf(BusinessException.class);

        verify(outboxRecorder, never()).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName("존재하지 않는 플레이리스트 구독 시도 시 RESOURCE_NOT_FOUND 예외가 발생한다")
    void subscribe_fail_notFound() {
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> playlistService.subscribe(PLAYLIST_ID, OTHER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ── unsubscribe ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("구독 취소 성공 시 rows affected 1 을 얻고 subscriberCount 를 한 번 감소시킨다")
    void unsubscribe_success() {
        Playlist playlist = savedPlaylist(PLAYLIST_ID, OWNER_ID, "제목", "설명", Instant.now());
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));
        when(subscriptionRepository.deleteByPlaylistIdAndSubscriberIdReturningCount(
                PLAYLIST_ID.toString(), OTHER_ID.toString()))
                .thenReturn(1);

        playlistService.unsubscribe(PLAYLIST_ID, OTHER_ID);

        verify(subscriptionRepository).deleteByPlaylistIdAndSubscriberIdReturningCount(
                PLAYLIST_ID.toString(), OTHER_ID.toString());
        verify(playlistRepository).decrementSubscriberCount(PLAYLIST_ID);
    }

    @Test
    @DisplayName("구독하지 않은 상태에서 재취소해도 예외 없이 204 흐름으로 종료되고 카운터도 감소하지 않는다")
    void unsubscribe_notSubscribed_isIdempotent() {
        // 플레이리스트는 존재하지만 해당 사용자의 구독 row 는 없는 상황.
        Playlist playlist = savedPlaylist(PLAYLIST_ID, OWNER_ID, "제목", "설명", Instant.now());
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));
        when(subscriptionRepository.deleteByPlaylistIdAndSubscriberIdReturningCount(
                PLAYLIST_ID.toString(), OTHER_ID.toString()))
                .thenReturn(0);

        // 예외 없이 정상 종료해야 하며 (컨트롤러가 204 로 응답한다)
        playlistService.unsubscribe(PLAYLIST_ID, OTHER_ID);

        // 실제 DELETE 는 시도되어야 rows=0 을 확인할 수 있다.
        verify(subscriptionRepository).deleteByPlaylistIdAndSubscriberIdReturningCount(
                PLAYLIST_ID.toString(), OTHER_ID.toString());
        // rows=0 경로에서는 카운터를 감소시키지 않아 실구독 수보다 낮게 떨어지지 않는다.
        verify(playlistRepository, never()).decrementSubscriberCount(any(UUID.class));
        verify(subscriptionRepository, never())
                .existsByPlaylistIdAndSubscriberId(any(UUID.class), any(UUID.class));
    }

    @Test
    @DisplayName("존재하지 않는 플레이리스트를 취소 시도하면 RESOURCE_NOT_FOUND 예외가 발생한다")
    void unsubscribe_fail_playlistNotFound() {
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> playlistService.unsubscribe(PLAYLIST_ID, OTHER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        verify(subscriptionRepository, never())
                .deleteByPlaylistIdAndSubscriberIdReturningCount(anyString(), anyString());
        verify(playlistRepository, never()).decrementSubscriberCount(any(UUID.class));
    }

    @Test
    @DisplayName("동시 unsubscribe race 로 rows affected 가 0 이면 decrement 를 호출하지 않는다")
    void unsubscribe_noDecrement_whenRaceLosesRow() {
        // 두 트랜잭션이 동시에 진입해 한 쪽만 실제 DELETE 를 성공시킨 race 를 시뮬레이션한다.
        Playlist playlist = savedPlaylist(PLAYLIST_ID, OWNER_ID, "제목", "설명", Instant.now());
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));
        when(subscriptionRepository.deleteByPlaylistIdAndSubscriberIdReturningCount(
                PLAYLIST_ID.toString(), OTHER_ID.toString()))
                .thenReturn(0);

        playlistService.unsubscribe(PLAYLIST_ID, OTHER_ID);

        // race 경로에서도 실제 DELETE 는 시도되어야 rows=0 을 통해 상황을 판정할 수 있다.
        verify(subscriptionRepository).deleteByPlaylistIdAndSubscriberIdReturningCount(
                PLAYLIST_ID.toString(), OTHER_ID.toString());
        // rows=0 경로에서는 카운터를 감소시키지 않아 실구독 수보다 낮게 떨어지지 않는다.
        verify(playlistRepository, never()).decrementSubscriberCount(any(UUID.class));
    }

    // ── getSubscribers ────────────────────────────────────────────────────────

    @Test
    @DisplayName("getSubscribers 는 최근순 결과를 CursorResponse 로 매핑한다 (hasNext=false)")
    void getSubscribers_firstPage_noNext() {
        Playlist playlist = savedPlaylist(PLAYLIST_ID, OWNER_ID, "제목", "설명", java.time.Instant.now());
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(java.util.Optional.of(playlist));

        java.time.Instant t1 = java.time.Instant.parse("2026-08-01T10:00:00Z");
        java.time.Instant t2 = java.time.Instant.parse("2026-08-01T11:00:00Z");
        UUID subId1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID subId2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID subscriber1 = UUID.randomUUID();
        UUID subscriber2 = UUID.randomUUID();
        PlaylistSubscription s1 = savedSubscriptionWithCreatedAt(subId1, PLAYLIST_ID, subscriber1, t2);
        PlaylistSubscription s2 = savedSubscriptionWithCreatedAt(subId2, PLAYLIST_ID, subscriber2, t1);

        when(subscriptionRepository.findByPlaylistIdDesc(
                org.mockito.ArgumentMatchers.eq(PLAYLIST_ID.toString()),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(11)))
                .thenReturn(List.of(s1, s2));
        when(subscriptionRepository.countByPlaylistId(PLAYLIST_ID)).thenReturn(2L);

        com.mopl.global.common.CursorResponse<com.mopl.playlist.dto.SubscriberItemDto> result =
                playlistService.getSubscribers(PLAYLIST_ID, null, null, 10, "subscribedAt", "DESCENDING");

        assertThat(result.data()).hasSize(2);
        assertThat(result.data().get(0).subscriptionId()).isEqualTo(subId1);
        assertThat(result.data().get(0).user().userId()).isEqualTo(subscriber1);
        assertThat(result.data().get(0).subscribedAt()).isEqualTo(t2);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.nextCursor()).isNull();
        assertThat(result.totalCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("getSubscribers 는 결과 수가 limit+1 이면 hasNext=true 와 nextCursor 를 설정한다")
    void getSubscribers_hasNext() {
        Playlist playlist = savedPlaylist(PLAYLIST_ID, OWNER_ID, "제목", "설명", java.time.Instant.now());
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(java.util.Optional.of(playlist));

        java.time.Instant base = java.time.Instant.parse("2026-08-01T10:00:00Z");
        PlaylistSubscription s1 = savedSubscriptionWithCreatedAt(
                UUID.randomUUID(), PLAYLIST_ID, UUID.randomUUID(), base.plusSeconds(30));
        PlaylistSubscription s2 = savedSubscriptionWithCreatedAt(
                UUID.randomUUID(), PLAYLIST_ID, UUID.randomUUID(), base.plusSeconds(20));
        PlaylistSubscription s3 = savedSubscriptionWithCreatedAt(
                UUID.randomUUID(), PLAYLIST_ID, UUID.randomUUID(), base.plusSeconds(10));

        when(subscriptionRepository.findByPlaylistIdDesc(
                org.mockito.ArgumentMatchers.eq(PLAYLIST_ID.toString()),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(3)))
                .thenReturn(List.of(s1, s2, s3));
        when(subscriptionRepository.countByPlaylistId(PLAYLIST_ID)).thenReturn(5L);

        com.mopl.global.common.CursorResponse<com.mopl.playlist.dto.SubscriberItemDto> result =
                playlistService.getSubscribers(PLAYLIST_ID, null, null, 2, "subscribedAt", "DESCENDING");

        assertThat(result.data()).hasSize(2);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.nextCursor()).isEqualTo(
                com.mopl.global.util.CursorUtils.encodeInstant(s2.getCreatedAt()));
        assertThat(result.nextIdAfter()).isEqualTo(s2.getId());
    }

    @Test
    @DisplayName("존재하지 않는 플레이리스트의 구독자 조회 시 RESOURCE_NOT_FOUND 예외가 발생한다")
    void getSubscribers_fail_notFound() {
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> playlistService.getSubscribers(
                PLAYLIST_ID, null, null, 10, "subscribedAt", "DESCENDING"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        verifyNoInteractions(subscriptionRepository);
    }

    @Test
    @DisplayName("cursor 만 있고 idAfter 가 없으면 INVALID_INPUT 예외가 발생한다")
    void getSubscribers_fail_cursorWithoutIdAfter() {
        Playlist playlist = savedPlaylist(PLAYLIST_ID, OWNER_ID, "제목", "설명", java.time.Instant.now());
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(java.util.Optional.of(playlist));

        String validCursor = com.mopl.global.util.CursorUtils.encodeInstant(java.time.Instant.now());

        assertThatThrownBy(() -> playlistService.getSubscribers(
                PLAYLIST_ID, validCursor, null, 10, "subscribedAt", "DESCENDING"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("잘못된 cursor 값이면 INVALID_INPUT 예외가 발생한다")
    void getSubscribers_fail_invalidCursor() {
        Playlist playlist = savedPlaylist(PLAYLIST_ID, OWNER_ID, "제목", "설명", java.time.Instant.now());
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(java.util.Optional.of(playlist));

        assertThatThrownBy(() -> playlistService.getSubscribers(
                PLAYLIST_ID, "not-base64!!", UUID.randomUUID(), 10, "subscribedAt", "DESCENDING"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("getSubscribers 는 페이지 subscriber ID 를 배치 조회해 user.name/profileImageUrl 을 채운다")
    void getSubscribers_populatesUserNameAndProfileImageUrl() {
        Playlist playlist = savedPlaylist(PLAYLIST_ID, OWNER_ID, "제목", "설명", java.time.Instant.now());
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(java.util.Optional.of(playlist));

        UUID sub1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID sub2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
        PlaylistSubscription s1 = savedSubscriptionWithCreatedAt(
                UUID.randomUUID(), PLAYLIST_ID, sub1, java.time.Instant.parse("2026-08-01T11:00:00Z"));
        PlaylistSubscription s2 = savedSubscriptionWithCreatedAt(
                UUID.randomUUID(), PLAYLIST_ID, sub2, java.time.Instant.parse("2026-08-01T10:00:00Z"));
        User user1 = savedUser(sub1, "userA", "https://cdn/a.png");
        User user2 = savedUser(sub2, "userB", "https://cdn/b.png");

        when(subscriptionRepository.findByPlaylistIdDesc(anyString(), any(), any(), org.mockito.ArgumentMatchers.eq(11)))
                .thenReturn(List.of(s1, s2));
        when(subscriptionRepository.countByPlaylistId(PLAYLIST_ID)).thenReturn(2L);
        when(userRepository.findAllById(any())).thenReturn(List.of(user1, user2));

        com.mopl.global.common.CursorResponse<com.mopl.playlist.dto.SubscriberItemDto> result =
                playlistService.getSubscribers(PLAYLIST_ID, null, null, 10, "subscribedAt", "DESCENDING");

        assertThat(result.data()).hasSize(2);
        assertThat(result.data().get(0).user().name()).isEqualTo("userA");
        assertThat(result.data().get(0).user().profileImageUrl()).isEqualTo("https://cdn/a.png");
        assertThat(result.data().get(1).user().name()).isEqualTo("userB");
        assertThat(result.data().get(1).user().profileImageUrl()).isEqualTo("https://cdn/b.png");
        // N+1 방지 검증: subscriber 수와 무관하게 findAllById 1회 호출
        verify(userRepository).findAllById(any());
    }

    @Test
    @DisplayName("getSubscribers 는 user 조회 결과에 없는 subscriber 에 대해 UNKNOWN fallback 을 반환한다")
    void getSubscribers_fallbackToUnknownWhenUserMissing() {
        Playlist playlist = savedPlaylist(PLAYLIST_ID, OWNER_ID, "제목", "설명", java.time.Instant.now());
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(java.util.Optional.of(playlist));

        UUID sub1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        PlaylistSubscription s1 = savedSubscriptionWithCreatedAt(
                UUID.randomUUID(), PLAYLIST_ID, sub1, java.time.Instant.parse("2026-08-01T11:00:00Z"));

        when(subscriptionRepository.findByPlaylistIdDesc(anyString(), any(), any(), org.mockito.ArgumentMatchers.eq(11)))
                .thenReturn(List.of(s1));
        when(subscriptionRepository.countByPlaylistId(PLAYLIST_ID)).thenReturn(1L);
        when(userRepository.findAllById(any())).thenReturn(List.of());

        com.mopl.global.common.CursorResponse<com.mopl.playlist.dto.SubscriberItemDto> result =
                playlistService.getSubscribers(PLAYLIST_ID, null, null, 10, "subscribedAt", "DESCENDING");

        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).user().userId()).isEqualTo(sub1);
        assertThat(result.data().get(0).user().name()).isEqualTo("알 수 없는 사용자");
        assertThat(result.data().get(0).user().profileImageUrl()).isNull();
    }

    // ── Phase D: 남은 조건 분기 커버 ─────────────────────────────────────────

    @Test
    @DisplayName("get 은 requesterId 가 있고 구독 이력이 없으면 subscribedByMe=false 를 반환한다")
    void get_requesterNotSubscribed_returnsSubscribedByMeFalse() {
        Playlist playlist = savedPlaylist(PLAYLIST_ID, OWNER_ID, "제목", "설명", Instant.now());
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));
        when(subscriptionRepository.existsByPlaylistIdAndSubscriberId(PLAYLIST_ID, OTHER_ID)).thenReturn(false);

        PlaylistDto result = playlistService.get(PLAYLIST_ID, OTHER_ID);

        assertThat(result.subscribedByMe()).isFalse();
    }

    @Test
    @DisplayName("getList 는 requesterId 가 있고 페이지가 비어있지 않으면 구독 여부를 배치 조회하고 subscribedByMe 를 매핑한다")
    void getList_requesterIdAndNonEmptyPage_mapsSubscribedByMe() {
        UUID subscribedId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        List<Playlist> rows = List.of(
                savedPlaylist(subscribedId, OWNER_ID, "S", "s", Instant.now()),
                savedPlaylist(otherId, OWNER_ID, "O", "o", Instant.now())
        );
        when(playlistRepository.findByUpdatedAtAsc(null, null, null, null, null, 3)).thenReturn(rows);
        when(playlistRepository.countByFilter(null, null, null)).thenReturn(2L);
        when(subscriptionRepository.findSubscribedPlaylistIds(OTHER_ID, List.of(subscribedId, otherId)))
                .thenReturn(Set.of(subscribedId));

        CursorResponse<PlaylistDto> result = playlistService.getList(
                null, null, null, null, null, 2, "updatedAt", "ASCENDING", OTHER_ID);

        assertThat(result.data()).hasSize(2);
        assertThat(result.data().get(0).subscribedByMe()).isTrue();
        assertThat(result.data().get(1).subscribedByMe()).isFalse();
    }

    @Test
    @DisplayName("get 은 콘텐츠 타입 TV_SERIES 를 tvSeries 문자열로 매핑한다")
    void get_mapsContentTypeTvSeries() {
        Playlist playlist = savedPlaylist(PLAYLIST_ID, OWNER_ID, "제목", "설명", Instant.now());
        UUID contentId = UUID.randomUUID();
        Content content = savedContentWithType(contentId, "TV", ContentType.TV_SERIES);

        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));
        when(playlistContentRepository.findAllByPlaylistIdOrderByCreatedAtAsc(PLAYLIST_ID))
                .thenReturn(List.of(savedLink(PLAYLIST_ID, contentId, Instant.now())));
        when(contentRepository.findAllWithTagsByIdIn(List.of(contentId)))
                .thenReturn(List.of(content));

        PlaylistDto result = playlistService.get(PLAYLIST_ID, null);

        assertThat(result.contents()).hasSize(1);
        assertThat(result.contents().get(0).type()).isEqualTo("tvSeries");
    }

    @Test
    @DisplayName("get 은 콘텐츠 타입 SPORT 를 sport 문자열로 매핑한다")
    void get_mapsContentTypeSport() {
        Playlist playlist = savedPlaylist(PLAYLIST_ID, OWNER_ID, "제목", "설명", Instant.now());
        UUID contentId = UUID.randomUUID();
        Content content = savedContentWithType(contentId, "축구", ContentType.SPORT);

        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));
        when(playlistContentRepository.findAllByPlaylistIdOrderByCreatedAtAsc(PLAYLIST_ID))
                .thenReturn(List.of(savedLink(PLAYLIST_ID, contentId, Instant.now())));
        when(contentRepository.findAllWithTagsByIdIn(List.of(contentId)))
                .thenReturn(List.of(content));

        PlaylistDto result = playlistService.get(PLAYLIST_ID, null);

        assertThat(result.contents().get(0).type()).isEqualTo("sport");
    }

    @Test
    @DisplayName("get 은 링크된 콘텐츠 ID 가 ContentRepository 결과에 없으면 해당 항목을 조용히 제외한다")
    void get_dropsMissingContentSummary() {
        Playlist playlist = savedPlaylist(PLAYLIST_ID, OWNER_ID, "제목", "설명", Instant.now());
        UUID existingContentId = UUID.randomUUID();
        UUID missingContentId = UUID.randomUUID();

        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));
        when(playlistContentRepository.findAllByPlaylistIdOrderByCreatedAtAsc(PLAYLIST_ID))
                .thenReturn(List.of(
                        savedLink(PLAYLIST_ID, existingContentId, Instant.now()),
                        savedLink(PLAYLIST_ID, missingContentId, Instant.now())));
        when(contentRepository.findAllWithTagsByIdIn(List.of(existingContentId, missingContentId)))
                .thenReturn(List.of(savedContent(existingContentId, "존재")));

        PlaylistDto result = playlistService.get(PLAYLIST_ID, null);

        assertThat(result.contents()).hasSize(1);
        assertThat(result.contents().get(0).id()).isEqualTo(existingContentId);
    }

    @Test
    @DisplayName("addContent 는 DuplicateKey 가 아닌 DataIntegrityViolationException 은 그대로 전파한다")
    void addContent_propagates_nonDuplicateKeyIntegrityViolation() {
        Playlist playlist = savedPlaylist(PLAYLIST_ID, OWNER_ID, "제목", "설명", Instant.now());
        UUID contentId = UUID.randomUUID();

        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));
        when(contentRepository.existsById(contentId)).thenReturn(true);
        when(playlistContentRepository.existsByPlaylistIdAndContentId(PLAYLIST_ID, contentId)).thenReturn(false);
        DataIntegrityViolationException nonUniqueViolation = new DataIntegrityViolationException("FK violation");
        doThrow(nonUniqueViolation).when(playlistContentSaver).save(PLAYLIST_ID, contentId);

        assertThatThrownBy(() -> playlistService.addContent(PLAYLIST_ID, contentId, OWNER_ID))
                .isSameAs(nonUniqueViolation);
    }

    @Test
    @DisplayName("addContent 는 DuplicateKeyException 을 조용히 흡수한다")
    void addContent_silentlyIgnores_duplicateKeyException() {
        Playlist playlist = savedPlaylist(PLAYLIST_ID, OWNER_ID, "제목", "설명", Instant.now());
        UUID contentId = UUID.randomUUID();

        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));
        when(contentRepository.existsById(contentId)).thenReturn(true);
        when(playlistContentRepository.existsByPlaylistIdAndContentId(PLAYLIST_ID, contentId)).thenReturn(false);
        doThrow(new DuplicateKeyException("duplicate")).when(playlistContentSaver).save(PLAYLIST_ID, contentId);

        // 예외 없이 정상 반환 (동시 삽입 race 흡수)
        playlistService.addContent(PLAYLIST_ID, contentId, OWNER_ID);
        verify(playlistContentSaver).save(PLAYLIST_ID, contentId);
    }

    // ── owner 필드 배치 조회 (이슈 #182) ────────────────────────────────────

    @Test
    @DisplayName("get 은 owner.name·profileImageUrl 을 실제 User 정보로 채운다")
    void get_success_populatesOwnerNameAndProfileImageUrl() {
        Playlist playlist = savedPlaylist(PLAYLIST_ID, OWNER_ID, "제목", "설명", Instant.now());
        User owner = savedUser(OWNER_ID, "홍길동", "https://example.com/avatar.png");

        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));
        when(userRepository.findAllById(List.of(OWNER_ID))).thenReturn(List.of(owner));

        PlaylistDto result = playlistService.get(PLAYLIST_ID, null);

        assertThat(result.owner().userId()).isEqualTo(OWNER_ID);
        assertThat(result.owner().name()).isEqualTo("홍길동");
        assertThat(result.owner().profileImageUrl()).isEqualTo("https://example.com/avatar.png");
    }

    @Test
    @DisplayName("get 은 owner user 가 조회되지 않으면 알 수 없는 사용자 대체값을 사용한다")
    void get_userNotFound_useUnknownName() {
        Playlist playlist = savedPlaylist(PLAYLIST_ID, OWNER_ID, "제목", "설명", Instant.now());
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));
        when(userRepository.findAllById(List.of(OWNER_ID))).thenReturn(List.of());

        PlaylistDto result = playlistService.get(PLAYLIST_ID, null);

        assertThat(result.owner().userId()).isEqualTo(OWNER_ID);
        assertThat(result.owner().name()).isEqualTo("알 수 없는 사용자");
        assertThat(result.owner().profileImageUrl()).isNull();
    }

    @Test
    @DisplayName("getList 는 페이지 각 항목의 owner.name·profileImageUrl 을 실제 User 정보로 채운다")
    void getList_populatesOwnerFieldsForEachItem() {
        UUID ownerA = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID ownerB = UUID.fromString("22222222-2222-2222-2222-222222222222");
        List<Playlist> rows = List.of(
                savedPlaylist(UUID.randomUUID(), ownerA, "A", "a", Instant.now()),
                savedPlaylist(UUID.randomUUID(), ownerB, "B", "b", Instant.now())
        );
        when(playlistRepository.findByUpdatedAtAsc(null, null, null, null, null, 3)).thenReturn(rows);
        when(playlistRepository.countByFilter(null, null, null)).thenReturn(2L);
        when(userRepository.findAllById(any())).thenReturn(List.of(
                savedUser(ownerA, "사용자A", "https://a.png"),
                savedUser(ownerB, "사용자B", null)
        ));

        CursorResponse<PlaylistDto> result = playlistService.getList(
                null, null, null, null, null, 2, "updatedAt", "ASCENDING", null);

        assertThat(result.data()).hasSize(2);
        assertThat(result.data().get(0).owner().userId()).isEqualTo(ownerA);
        assertThat(result.data().get(0).owner().name()).isEqualTo("사용자A");
        assertThat(result.data().get(0).owner().profileImageUrl()).isEqualTo("https://a.png");
        assertThat(result.data().get(1).owner().userId()).isEqualTo(ownerB);
        assertThat(result.data().get(1).owner().name()).isEqualTo("사용자B");
        assertThat(result.data().get(1).owner().profileImageUrl()).isNull();
    }

    @Test
    @DisplayName("getList 는 페이지 크기와 무관하게 owner 를 findAllById 로 1회만 배치 조회한다 (N+1 방지)")
    @SuppressWarnings("unchecked")
    void getList_batchFetchesOwnersOnce() {
        UUID ownerA = UUID.fromString("aaaaaaa1-0000-0000-0000-000000000000");
        UUID ownerB = UUID.fromString("bbbbbbb2-0000-0000-0000-000000000000");
        List<Playlist> rows = List.of(
                savedPlaylist(UUID.randomUUID(), ownerA, "A", "a", Instant.now()),
                savedPlaylist(UUID.randomUUID(), ownerA, "B", "b", Instant.now()),
                savedPlaylist(UUID.randomUUID(), ownerB, "C", "c", Instant.now())
        );
        when(playlistRepository.findByUpdatedAtAsc(null, null, null, null, null, 4)).thenReturn(rows);
        when(playlistRepository.countByFilter(null, null, null)).thenReturn(3L);
        when(userRepository.findAllById(any())).thenReturn(List.of(
                savedUser(ownerA, "사용자A", null),
                savedUser(ownerB, "사용자B", null)
        ));

        playlistService.getList(null, null, null, null, null, 3, "updatedAt", "ASCENDING", null);

        // 배치 조회 계약: findAllById 1회, findById 는 호출되지 않음
        ArgumentCaptor<Iterable<UUID>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(userRepository, times(1)).findAllById(captor.capture());
        verify(userRepository, never()).findById(any(UUID.class));

        // ownerIds 는 distinct 로 전달되어야 함 (ownerA 중복 제거)
        assertThat(captor.getValue()).containsExactlyInAnyOrder(ownerA, ownerB);
    }

    // ── getPopular ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("getPopular — cursor 만 있고 idAfter 가 없으면 INVALID_INPUT")
    void getPopular_orphanCursorPair_throws400() {
        String cursor = CursorUtils.encodePopularCursor(5L, Instant.parse("2026-08-12T00:00:00Z"));

        assertThatThrownBy(() -> playlistService.getPopular(cursor, null, 10, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("getPopular — 잘못된 커서 형식은 INVALID_INPUT")
    void getPopular_invalidCursorFormat_throws400() {
        String malformed = CursorUtils.encode("not-a-popular-cursor");

        assertThatThrownBy(() -> playlistService.getPopular(malformed, UUID.randomUUID(), 10, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("getPopular — owner 정보를 findAllById 1회 배치 호출로 채운다 (distinct)")
    void getPopular_batchFetchesOwnersOnce() {
        UUID ownerA = UUID.randomUUID();
        UUID ownerB = UUID.randomUUID();
        Playlist p1 = savedPlaylist(UUID.randomUUID(), ownerA, "A", "a", Instant.now());
        Playlist p2 = savedPlaylist(UUID.randomUUID(), ownerA, "B", "b", Instant.now()); // ownerA 중복
        Playlist p3 = savedPlaylist(UUID.randomUUID(), ownerB, "C", "c", Instant.now());

        when(playlistRepository.findPopular(any(), any(), any(), anyInt()))
                .thenReturn(List.of(p1, p2, p3));
        when(playlistRepository.countByFilter(any(), any(), any())).thenReturn(3L);
        when(userRepository.findAllById(any())).thenReturn(List.of(
                savedUser(ownerA, "UserA", null),
                savedUser(ownerB, "UserB", null)));

        playlistService.getPopular(null, null, 10, null);

        // findAllById(Iterable) 에는 List·Set 등 어떤 Collection 도 전달 가능하므로 Iterable 로 캡처한다.
        ArgumentCaptor<Iterable<UUID>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(userRepository, times(1)).findAllById(captor.capture());
        // ownerIds 는 distinct 로 전달되어야 함
        assertThat(captor.getValue()).containsExactlyInAnyOrder(ownerA, ownerB);
    }

    @Test
    @DisplayName("getPopular — 응답에 sortBy=subscriberCount·sortDirection=DESCENDING 이 포함된다")
    void getPopular_success_returnsSortMetadata() {
        UUID owner = UUID.randomUUID();
        Playlist p = savedPlaylist(UUID.randomUUID(), owner, "P", "d", Instant.now());

        when(playlistRepository.findPopular(any(), any(), any(), anyInt())).thenReturn(List.of(p));
        when(playlistRepository.countByFilter(any(), any(), any())).thenReturn(1L);
        when(userRepository.findAllById(any())).thenReturn(List.of(savedUser(owner, "U", null)));

        CursorResponse<PlaylistDto> result = playlistService.getPopular(null, null, 10, null);

        assertThat(result.sortBy()).isEqualTo("subscriberCount");
        assertThat(result.sortDirection()).isEqualTo("DESCENDING");
        assertThat(result.hasNext()).isFalse();
        assertThat(result.totalCount()).isEqualTo(1L);
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private Content savedContentWithType(UUID id, String title, ContentType type) {
        Content c = Content.builder()
                .type(type)
                .title(title)
                .description("설명")
                .build();
        ReflectionTestUtils.setField(c, "id", id);
        return c;
    }

    private Playlist savedPlaylist(UUID id, UUID ownerId, String title, String desc, Instant updatedAt) {
        Playlist p = Playlist.builder().ownerId(ownerId).title(title).description(desc).build();
        ReflectionTestUtils.setField(p, "id", id);
        ReflectionTestUtils.setField(p, "updatedAt", updatedAt);
        return p;
    }

    private PlaylistSubscription savedSubscription(UUID playlistId, UUID subscriberId) {
        return PlaylistSubscription.builder()
                .playlistId(playlistId)
                .subscriberId(subscriberId)
                .build();
    }

    private PlaylistSubscription savedSubscriptionWithCreatedAt(
            UUID id, UUID playlistId, UUID subscriberId, java.time.Instant createdAt) {
        PlaylistSubscription sub = savedSubscription(playlistId, subscriberId);
        ReflectionTestUtils.setField(sub, "id", id);
        ReflectionTestUtils.setField(sub, "createdAt", createdAt);
        return sub;
    }

    private Content savedContent(UUID id, String title) {
        Content c = Content.builder()
                .type(ContentType.MOVIE)
                .title(title)
                .description("설명")
                .build();
        ReflectionTestUtils.setField(c, "id", id);
        return c;
    }

    private PlaylistContent savedLink(UUID playlistId, UUID contentId, Instant createdAt) {
        PlaylistContent link = PlaylistContent.create(playlistId, contentId);
        ReflectionTestUtils.setField(link, "createdAt", createdAt);
        return link;
    }

    private User savedUser(UUID id, String name, String profileImageUrl) {
        User user = User.builder()
                .email(id + "@example.com")
                .passwordHash("hash")
                .name(name)
                .profileImageUrl(profileImageUrl)
                .role(UserRole.USER)
                .locked(false)
                .build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}