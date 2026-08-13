package com.mopl.user.service;

import com.mopl.user.dto.JwtDto;

/**
 * Refresh Token Rotation을 통한 토큰 재발급 결과
 *
 * <p>클라이언트에 JSON으로 반환할 사용자 정보와 새 Access Token은
 * {@link JwtDto}에 담습니다.</p>
 *
 * <p>새 Refresh Token 원문은 JSON 응답에 포함하지 않고 HttpOnly Cookie로
 * 전달해야 하므로 {@link IssuedRefreshToken}으로 분리합니다.</p>
 *
 * @param jwtDto 새 Access Token과 사용자 정보를 담은 응답 데이터
 * @param issuedRefreshToken 새 HttpOnly Cookie를 만들기 위한 Refresh Token 발급 결과
 */
public record RefreshResult(
    JwtDto jwtDto,
    IssuedRefreshToken issuedRefreshToken
) {
}
