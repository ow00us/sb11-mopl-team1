package com.mopl.playlist.dto;

import com.mopl.global.common.UserSummary;

import java.time.Instant;
import java.util.UUID;

/**
 * 플레이리스트 구독자 목록 조회 응답의 아이템 스키마.
 * <p>{@code user.name} / {@code user.profileImageUrl} 은 페이지 subscriber ID 를 배치 조회해 채우며,
 * 대응하는 사용자가 없으면 UNKNOWN fallback 을 반환한다.
 */
public record SubscriberItemDto(
        UUID subscriptionId,
        UserSummary user,
        Instant subscribedAt
) {
}