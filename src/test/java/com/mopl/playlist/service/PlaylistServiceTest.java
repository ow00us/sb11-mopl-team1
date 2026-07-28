package com.mopl.playlist.service;

import com.mopl.global.common.CursorResponse;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.playlist.dto.PlaylistCreateRequest;
import com.mopl.playlist.dto.PlaylistDto;
import com.mopl.playlist.dto.PlaylistUpdateRequest;
import com.mopl.playlist.entity.Playlist;
import com.mopl.playlist.repository.PlaylistRepository;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaylistServiceTest {

    @Mock
    PlaylistRepository playlistRepository;

    @InjectMocks
    PlaylistServiceImpl playlistService;

    private static final UUID OWNER_ID    = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_ID    = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID PLAYLIST_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

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

        PlaylistDto result = playlistService.get(PLAYLIST_ID);

        assertThat(result.id()).isEqualTo(PLAYLIST_ID);
        assertThat(result.title()).isEqualTo("제목");
    }

    @Test
    @DisplayName("존재하지 않는 ID 조회 시 RESOURCE_NOT_FOUND 예외가 발생한다")
    void get_fail_notFound() {
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> playlistService.get(PLAYLIST_ID))
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
        when(playlistRepository.findByUpdatedAtAsc(null, null, null, null, 3)).thenReturn(rows);
        when(playlistRepository.countByFilter(null, null)).thenReturn(2L);

        CursorResponse<PlaylistDto> result = playlistService.getList(
                null, null, null, null, 2, "updatedAt", "ASCENDING");

        assertThat(result.data()).hasSize(2);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.nextCursor()).isNull();
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
        when(playlistRepository.findByUpdatedAtAsc(null, null, null, null, 3)).thenReturn(rows);
        when(playlistRepository.countByFilter(null, null)).thenReturn(5L);

        CursorResponse<PlaylistDto> result = playlistService.getList(
                null, null, null, null, 2, "updatedAt", "ASCENDING");

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
        when(playlistRepository.findByUpdatedAtAsc(null, OWNER_ID.toString(), null, null, 2))
                .thenReturn(rows);
        when(playlistRepository.countByFilter(null, OWNER_ID.toString())).thenReturn(1L);

        CursorResponse<PlaylistDto> result = playlistService.getList(
                null, OWNER_ID, null, null, 1, "updatedAt", "ASCENDING");

        assertThat(result.data()).hasSize(1);
        assertThat(result.totalCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("잘못된 cursor 값이 들어오면 INVALID_INPUT 예외가 발생한다")
    void getList_fail_invalidCursor() {
        assertThatThrownBy(() -> playlistService.getList(
                null, null, "invalid-cursor!!", null, 10, "updatedAt", "ASCENDING"))
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

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private Playlist savedPlaylist(UUID id, UUID ownerId, String title, String desc, Instant updatedAt) {
        Playlist p = Playlist.builder().ownerId(ownerId).title(title).description(desc).build();
        ReflectionTestUtils.setField(p, "id", id);
        ReflectionTestUtils.setField(p, "updatedAt", updatedAt);
        return p;
    }
}