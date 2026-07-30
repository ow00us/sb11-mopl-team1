package com.mopl.playlist.service;

import com.mopl.content.repository.ContentRepository;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.playlist.entity.Playlist;
import com.mopl.playlist.entity.PlaylistContent;
import com.mopl.playlist.repository.PlaylistContentRepository;
import com.mopl.playlist.repository.PlaylistRepository;
import com.mopl.playlist.repository.PlaylistSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaylistContentServiceTest {

    @Mock PlaylistRepository playlistRepository;
    @Mock PlaylistSubscriptionRepository subscriptionRepository;
    @Mock PlaylistContentRepository playlistContentRepository;
    @Mock ContentRepository contentRepository;
    @Mock PlaylistContentSaver playlistContentSaver;
    @InjectMocks PlaylistServiceImpl playlistService;

    private static final UUID OWNER_ID      = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_ID      = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID PLAYLIST_ID   = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID CONTENT_ID    = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    private Playlist playlist;

    @BeforeEach
    void setUp() {
        playlist = Playlist.builder().ownerId(OWNER_ID).title("테스트").description("설명").build();
        ReflectionTestUtils.setField(playlist, "id", PLAYLIST_ID);
    }

    // ── addContent ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("소유자가 콘텐츠를 추가하면 별도 트랜잭션의 saver가 호출된다")
    void addContent_success() {
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));
        when(contentRepository.existsById(CONTENT_ID)).thenReturn(true);
        when(playlistContentRepository.existsByPlaylistIdAndContentId(PLAYLIST_ID, CONTENT_ID)).thenReturn(false);

        playlistService.addContent(PLAYLIST_ID, CONTENT_ID, OWNER_ID);

        verify(playlistContentSaver).save(PLAYLIST_ID, CONTENT_ID);
        verify(playlistContentRepository, never()).saveAndFlush(any(PlaylistContent.class));
    }

    @Test
    @DisplayName("이미 추가된 콘텐츠는 saver를 호출하지 않는다")
    void addContent_duplicate_ignored() {
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));
        when(contentRepository.existsById(CONTENT_ID)).thenReturn(true);
        when(playlistContentRepository.existsByPlaylistIdAndContentId(PLAYLIST_ID, CONTENT_ID)).thenReturn(true);

        playlistService.addContent(PLAYLIST_ID, CONTENT_ID, OWNER_ID);

        verify(playlistContentSaver, never()).save(any(), any());
        verify(playlistContentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("saver가 PostgreSQL unique_violation(23505)을 던지면 무시하고 정상 완료된다")
    void addContent_swallowsUniqueViolation() {
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));
        when(contentRepository.existsById(CONTENT_ID)).thenReturn(true);
        when(playlistContentRepository.existsByPlaylistIdAndContentId(PLAYLIST_ID, CONTENT_ID)).thenReturn(false);
        SQLException uniqueViolation = new SQLException("dup", "23505");
        doThrow(new DataIntegrityViolationException("dup", uniqueViolation))
                .when(playlistContentSaver).save(PLAYLIST_ID, CONTENT_ID);

        assertThatCode(() -> playlistService.addContent(PLAYLIST_ID, CONTENT_ID, OWNER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("saver가 Spring DuplicateKeyException을 던지면 무시하고 정상 완료된다")
    void addContent_swallowsDuplicateKeyException() {
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));
        when(contentRepository.existsById(CONTENT_ID)).thenReturn(true);
        when(playlistContentRepository.existsByPlaylistIdAndContentId(PLAYLIST_ID, CONTENT_ID)).thenReturn(false);
        doThrow(new DuplicateKeyException("dup"))
                .when(playlistContentSaver).save(PLAYLIST_ID, CONTENT_ID);

        assertThatCode(() -> playlistService.addContent(PLAYLIST_ID, CONTENT_ID, OWNER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("saver가 비중복 무결성 예외(FK 위반 등)를 던지면 그대로 전파한다")
    void addContent_propagatesNonDuplicateIntegrityViolation() {
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));
        when(contentRepository.existsById(CONTENT_ID)).thenReturn(true);
        when(playlistContentRepository.existsByPlaylistIdAndContentId(PLAYLIST_ID, CONTENT_ID)).thenReturn(false);
        SQLException fkViolation = new SQLException("fk", "23503");
        doThrow(new DataIntegrityViolationException("fk", fkViolation))
                .when(playlistContentSaver).save(PLAYLIST_ID, CONTENT_ID);

        assertThatThrownBy(() -> playlistService.addContent(PLAYLIST_ID, CONTENT_ID, OWNER_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("소유자가 아니면 콘텐츠 추가 시 FORBIDDEN 예외가 발생한다")
    void addContent_forbidden() {
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));

        assertThatThrownBy(() -> playlistService.addContent(PLAYLIST_ID, CONTENT_ID, OTHER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("존재하지 않는 콘텐츠 추가 시 RESOURCE_NOT_FOUND 예외가 발생한다")
    void addContent_contentNotFound() {
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));
        when(contentRepository.existsById(CONTENT_ID)).thenReturn(false);

        assertThatThrownBy(() -> playlistService.addContent(PLAYLIST_ID, CONTENT_ID, OWNER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ── removeContent ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("소유자가 콘텐츠를 삭제하면 제거된다")
    void removeContent_success() {
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));
        when(playlistContentRepository.existsByPlaylistIdAndContentId(PLAYLIST_ID, CONTENT_ID)).thenReturn(true);

        playlistService.removeContent(PLAYLIST_ID, CONTENT_ID, OWNER_ID);

        verify(playlistContentRepository).deleteByPlaylistIdAndContentId(PLAYLIST_ID, CONTENT_ID);
    }

    @Test
    @DisplayName("소유자가 아니면 콘텐츠 삭제 시 FORBIDDEN 예외가 발생한다")
    void removeContent_forbidden() {
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));

        assertThatThrownBy(() -> playlistService.removeContent(PLAYLIST_ID, CONTENT_ID, OTHER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("존재하지 않는 콘텐츠 삭제 시 RESOURCE_NOT_FOUND 예외가 발생한다")
    void removeContent_notFound() {
        when(playlistRepository.findById(PLAYLIST_ID)).thenReturn(Optional.of(playlist));
        when(playlistContentRepository.existsByPlaylistIdAndContentId(PLAYLIST_ID, CONTENT_ID)).thenReturn(false);

        assertThatThrownBy(() -> playlistService.removeContent(PLAYLIST_ID, CONTENT_ID, OWNER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }
}