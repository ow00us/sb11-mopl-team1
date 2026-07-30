package com.mopl.playlist.service;

import com.mopl.playlist.entity.PlaylistContent;
import com.mopl.playlist.repository.PlaylistContentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PlaylistContentSaverTest {

    @Mock PlaylistContentRepository playlistContentRepository;
    @InjectMocks PlaylistContentSaver saver;

    private static final UUID PLAYLIST_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID CONTENT_ID  = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    @Test
    @DisplayName("정상 저장 시 saveAndFlush를 호출한다")
    void save_success() {
        assertThatCode(() -> saver.save(PLAYLIST_ID, CONTENT_ID))
                .doesNotThrowAnyException();
        verify(playlistContentRepository).saveAndFlush(any(PlaylistContent.class));
    }

    @Test
    @DisplayName("무결성 예외는 호출자에게 그대로 전파한다")
    void save_propagatesIntegrityViolation() {
        doThrow(new DataIntegrityViolationException("dup"))
                .when(playlistContentRepository).saveAndFlush(any(PlaylistContent.class));

        assertThatThrownBy(() -> saver.save(PLAYLIST_ID, CONTENT_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}