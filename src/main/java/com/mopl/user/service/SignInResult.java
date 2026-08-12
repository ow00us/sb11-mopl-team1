package com.mopl.user.service;

import com.mopl.user.dto.JwtDto;

/**
 * 로그인 성공 후 Service가 Controller에 전달하는 내부 결과
 *
 * <p>JSON 응답으로 반환할 사용자 정보와 Access Token은 {@link JwtDto}에 담고,
 * HttpOnly Cookie로 전달할 Refresh Token은
 * {@link IssuedRefreshToken}에 분리하여 담습니다.</p>
 *
 * <p>이 객체 자체는 API 응답 DTO가 아니므로 Refresh Token이
 * JSON 응답에 직렬화되지 않습니다.</p>
 *
 * @param jwtDto JSON 본문으로 반환할 사용자 정보와 Access Token
 * @param issuedRefreshToken HttpOnly Cookie로 전달할 Refresh Token 발급 결과
 */
public record SignInResult(
    JwtDto jwtDto,
    IssuedRefreshToken issuedRefreshToken
) {
}
