package com.mopl.follow.dto;

import com.mopl.global.common.UserSummary;

/**
 * 팔로우 추천(친구의 친구) 목록 조회 응답의 아이템 스키마.
 * <p>{@code user} 는 요청자에게 추천되는 사용자이고,
 * {@code commonFollowingCount} 는 요청자와 겹치는 매개자(중간 사용자) 수이다.
 * 값이 클수록 요청자와 취향이 겹칠 가능성이 높은 후보다.
 */
public record FollowRecommendationItemDto(
        UserSummary user,
        long commonFollowingCount
) {
}