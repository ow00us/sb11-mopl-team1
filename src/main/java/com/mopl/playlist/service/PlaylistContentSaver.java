package com.mopl.playlist.service;

import com.mopl.playlist.entity.PlaylistContent;
import com.mopl.playlist.repository.PlaylistContentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PlaylistContentSaver {

    private final PlaylistContentRepository playlistContentRepository;

    // 동시 중복 삽입은 새 트랜잭션에서 시도해야 유니크 제약 위반 시 상위 트랜잭션이 rollback-only로 오염되지 않는다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveIgnoringDuplicate(UUID playlistId, UUID contentId) {
        try {
            playlistContentRepository.saveAndFlush(PlaylistContent.create(playlistId, contentId));
        } catch (DataIntegrityViolationException ignored) {
        }
    }
}