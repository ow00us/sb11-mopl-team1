package com.mopl.global.event;

import java.time.Instant;

/**
 * 리스너 컨테이너가 비정상 중지된 사실과 그 원인입니다.
 *
 * <p>컨테이너의 실행 여부는 spring-kafka 가 알고 있지만 왜 멈췄는지는 모릅니다. 운영자가
 * 먼저 알아야 하는 것은 "멈췄다"가 아니라 "무엇을 소비하다가 왜 멈췄다"입니다.
 *
 * @param groupId 멈춘 리스너의 Consumer Group
 * @param listenerId 멈춘 컨테이너의 식별자. 동시성 설정이 있으면 자식 컨테이너 식별자입니다
 * @param topic 마지막으로 처리하던 레코드의 원본 토픽
 * @param reason 중지 사유
 * @param stoppedAt 중지한 시각
 */
public record KafkaListenerStop(
    String groupId,
    String listenerId,
    String topic,
    String reason,
    Instant stoppedAt
) {
}
