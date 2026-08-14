package com.mopl.follow.repository;

import java.util.UUID;

/**
 * 팔로우 추천(친구의 친구) 쿼리 결과 한 행.
 * 후보 사용자 ID 와 요청자와의 공통 팔로잉 수만 담는다.
 * 사용자 이름·프로필은 UserRepository 배치 조회로 별도 채운다.
 */
public interface FollowRecommendationRow {

    UUID getUserId();

    long getCommonCount();
}