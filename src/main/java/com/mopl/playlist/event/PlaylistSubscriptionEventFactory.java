package com.mopl.playlist.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.event.EventEnvelope;
import com.mopl.playlist.entity.PlaylistSubscription;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * playlist 도메인 이벤트 envelope 를 조립합니다.
 *
 * <p>envelope 필드 값은 팀 공통 계약 {@code docs/07-kafka-outbox-contract.md} §8.2 가 정합니다.
 * PlaylistServiceImpl 는 이 팩토리에서 받은 envelope 를 그대로 Outbox 에 넘깁니다.
 */
@Component
@RequiredArgsConstructor
public class PlaylistSubscriptionEventFactory {

    @SuppressWarnings("unused")
    private final ObjectMapper objectMapper;

    /**
     * playlist.subscription.created 이벤트 envelope 를 만듭니다.
     *
     * <p>Green 커밋에서 계약 §8.2 대로 완성 예정 (스텁).
     */
    public EventEnvelope createSubscriptionCreatedEnvelope(
        PlaylistSubscription subscription, UUID playlistOwnerId) {
        throw new UnsupportedOperationException("Green 커밋에서 구현");
    }
}