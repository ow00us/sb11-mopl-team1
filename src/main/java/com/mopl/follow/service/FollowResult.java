package com.mopl.follow.service;

import com.mopl.follow.dto.FollowDto;

/**
 * 팔로우 생성 요청에 대한 서비스 결과.
 * <p>{@code created} 플래그로 새로 생성됐는지 (201) vs 이미 존재하는 관계였는지 (200) 를 구분한다.
 * openapi 계약: POST /api/follows 는 신규 201, 중복 요청 시 200 + 기존 FollowDto 를 반환한다.
 */
public record FollowResult(FollowDto dto, boolean created) {
}