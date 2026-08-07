package com.mopl.playlist.service;

import com.mopl.global.common.CursorResponse;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.util.CursorUtils;
import com.mopl.playlist.dto.PlaylistCreateRequest;
import com.mopl.playlist.dto.PlaylistDto;
import com.mopl.playlist.dto.PlaylistUpdateRequest;
import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentType;
import com.mopl.playlist.entity.Playlist;
import com.mopl.playlist.entity.PlaylistContent;
import com.mopl.playlist.entity.PlaylistSubscription;
import com.mopl.content.repository.ContentRepository;
import com.mopl.playlist.repository.PlaylistContentRepository;
import com.mopl.playlist.repository.PlaylistRepository;
import com.mopl.playlist.repository.PlaylistSubscriptionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaylistServiceTest {

    @Mock PlaylistRepository playlistRepository;
    @Mock PlaylistSubscriptionRepository subscriptionRepository;
    @Mock PlaylistContentRepository playlistContentRepository;
    @Mock ContentRepository contentRepository;
    @Mock PlaylistContentSaver playlistContentSaver;

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
        verify(contentRepository, never()).findAllById(anyList());

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
        Playlist playlist = savedPlaylist(PLAYLIST_ID, OWNER_ID, "제목", "설명", Instant.now());
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));
        when(subscriptionRepository.insertIfAbsent(PLAYLIST_ID.toString(), OTHER_ID.toString()))
                .thenReturn(1);

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
    @DisplayName("구독 취소 성공 시 삭제 flush 후 subscriberCount 를 감소시킨다")
    void unsubscribe_success() {
        PlaylistSubscription sub = savedSubscription(PLAYLIST_ID, OTHER_ID);
        when(subscriptionRepository.findByPlaylistIdAndSubscriberId(PLAYLIST_ID, OTHER_ID))
                .thenReturn(Optional.of(sub));

        playlistService.unsubscribe(PLAYLIST_ID, OTHER_ID);

        verify(subscriptionRepository).delete(sub);
        verify(subscriptionRepository).flush();
        verify(playlistRepository).decrementSubscriberCount(PLAYLIST_ID);
    }

    @Test
    @DisplayName("구독하지 않은 상태에서 취소 시도 시 RESOURCE_NOT_FOUND 예외가 발생한다")
    void unsubscribe_fail_notSubscribed() {
        when(subscriptionRepository.findByPlaylistIdAndSubscriberId(PLAYLIST_ID, OTHER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> playlistService.unsubscribe(PLAYLIST_ID, OTHER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
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

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

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
}