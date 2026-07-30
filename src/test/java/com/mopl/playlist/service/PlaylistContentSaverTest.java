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
    @DisplayName("정상 저장 시 예외 없이 완료된다")
    void saveIgnoringDuplicate_success() {
        assertThatCode(() -> saver.saveIgnoringDuplicate(PLAYLIST_ID, CONTENT_ID))
                .doesNotThrowAnyException();
        verify(playlistContentRepository).saveAndFlush(any(PlaylistContent.class));
    }

    @Test
    @DisplayName("유니크 제약 위반 시 예외를 삼키고 정상 반환한다")
    void saveIgnoringDuplicate_swallowsDuplicate() {
        doThrow(new DataIntegrityViolationException("duplicate"))
                .when(playlistContentRepository).saveAndFlush(any(PlaylistContent.class));

        assertThatCode(() -> saver.saveIgnoringDuplicate(PLAYLIST_ID, CONTENT_ID))
                .doesNotThrowAnyException();
    }
}