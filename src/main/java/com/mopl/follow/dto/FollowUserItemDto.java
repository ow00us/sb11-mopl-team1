package com.mopl.follow.dto;

import com.mopl.global.common.UserSummary;

import java.time.Instant;
import java.util.UUID;

/**
 * 팔로워/팔로잉 목록 조회 응답의 아이템 스키마.
 * <p>followers 응답에서는 {@code user} 가 팔로워(follower) 이고,
 * followings 응답에서는 {@code user} 가 팔로우 대상(followee) 이다.
 * {@code user.name} / {@code user.profileImageUrl} 은 User 도메인 연동 전까지 {@code null}.
 */
public record FollowUserItemDto(
        UUID followId,
        UserSummary user,
        Instant followedAt
) {
}