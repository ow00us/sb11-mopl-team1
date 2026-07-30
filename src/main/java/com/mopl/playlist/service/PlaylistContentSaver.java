package com.mopl.playlist.service;

import com.mopl.playlist.entity.PlaylistContent;
import com.mopl.playlist.repository.PlaylistContentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PlaylistContentSaver {

    private final PlaylistContentRepository playlistContentRepository;

    // 예외 처리는 호출자가 REQUIRES_NEW 트랜잭션 커밋 이후에 수행해야 rollback-only 오염을 피할 수 있으므로
    // 이 메서드는 예외를 잡지 않고 그대로 전파한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(UUID playlistId, UUID contentId) {
        playlistContentRepository.saveAndFlush(PlaylistContent.create(playlistId, contentId));
    }
}