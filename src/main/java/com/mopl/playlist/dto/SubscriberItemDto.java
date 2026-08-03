package com.mopl.playlist.dto;

import com.mopl.global.common.UserSummary;

import java.time.Instant;
import java.util.UUID;

/**
 * 플레이리스트 구독자 목록 조회 응답의 아이템 스키마.
 * <p>{@code user.name} / {@code user.profileImageUrl} 은 User 도메인 연동 전까지 {@code null}.
 */
public record SubscriberItemDto(
        UUID subscriptionId,
        UserSummary user,
        Instant subscribedAt
) {
}