package com.mopl.follow.dto;

import com.mopl.global.common.UserSummary;

import java.time.Instant;
import java.util.UUID;

/**
 * 팔로워/팔로잉 목록 조회 응답의 아이템 스키마.
 * <p>followers 응답에서는 {@code user} 가 팔로워(follower) 이고,
 * followings 응답에서는 {@code user} 가 팔로우 대상(followee) 이다.
 * {@code user.name} / {@code user.profileImageUrl} 은 페이지 user ID 를 배치 조회해 채우며,
 * 대응하는 사용자가 없으면 UNKNOWN fallback 을 반환한다.
 */
public record FollowUserItemDto(
        UUID followId,
        UserSummary user,
        Instant followedAt
) {
}