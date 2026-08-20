package com.mopl.playlist.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.event.EventEnvelope;
import com.mopl.playlist.entity.PlaylistSubscription;
import java.util.Map;
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

    private static final String TYPE_PLAYLIST_SUBSCRIPTION_CREATED = "playlist.subscription.created";
    private static final int VERSION = 1;

    private final ObjectMapper objectMapper;

    /**
     * playlist.subscription.created 이벤트 envelope 를 만듭니다.
     *
     * <p>계약 §8.2
     * <ul>
     *   <li>aggregate = subscriptionId</li>
     *   <li>occurredAt = subscription.createdAt (구독 행 생성 시각)</li>
     *   <li>payload = {@code {playlistId, playlistOwnerId, subscriberId}}</li>
     * </ul>
     *
     * @param subscription     신규 생성이 확정된 구독 행
     * @param playlistOwnerId  플레이리스트 소유자 (알림 수신자)
     */
    public EventEnvelope createSubscriptionCreatedEnvelope(
        PlaylistSubscription subscription, UUID playlistOwnerId) {
        return new EventEnvelope(
                UUID.randomUUID(),
                TYPE_PLAYLIST_SUBSCRIPTION_CREATED,
                VERSION,
                subscription.getCreatedAt(),
                subscription.getId(),
                objectMapper.valueToTree(Map.of(
                        "playlistId", subscription.getPlaylistId().toString(),
                        "playlistOwnerId", playlistOwnerId.toString(),
                        "subscriberId", subscription.getSubscriberId().toString())));
    }
}