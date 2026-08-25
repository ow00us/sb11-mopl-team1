package com.mopl.follow.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.follow.entity.Follow;
import com.mopl.global.event.EventEnvelope;
import com.mopl.global.event.KafkaEventContract;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * follow 도메인 이벤트 envelope 를 조립합니다.
 *
 * <p>envelope 필드 값은 팀 공통 계약 {@code docs/07-kafka-outbox-contract.md} §8.1 이 정합니다.
 * FollowService 는 이 팩토리에서 받은 envelope 를 그대로 Outbox 에 넘깁니다.
 */
@Component
@RequiredArgsConstructor
public class FollowEventFactory {

    private final ObjectMapper objectMapper;

    /**
     * follow.created 이벤트 envelope 를 만듭니다.
     *
     * <p>계약 §8.1
     * <ul>
     *   <li>aggregate = followId</li>
     *   <li>occurredAt = follow.createdAt (팔로우 행 생성 시각)</li>
     *   <li>payload = {@code {followerId, followeeId}}</li>
     * </ul>
     *
     * @param follow 신규 생성이 확정된 팔로우 (재시도 신규 판정 포함)
     */
    public EventEnvelope createFollowCreatedEnvelope(Follow follow) {
        return new EventEnvelope(
                UUID.randomUUID(),
                KafkaEventContract.FOLLOW_CREATED.type(),
                KafkaEventContract.FOLLOW_CREATED.version(),
                follow.getCreatedAt(),
                follow.getId(),
                objectMapper.valueToTree(Map.of(
                        "followerId", follow.getFollowerId().toString(),
                        "followeeId", follow.getFolloweeId().toString())));
    }
}
