package com.mopl.user.dto;

/**
 * 로그인 성공 후 클라이언트에 반환하는 JWT 액세스 토큰 응답
 *
 * accessToken은 이후 인증이 필요한 API의 Authorization 헤더에 사용
 * userDto는 로그인 직후 프론트엔드가 현재 사용자 정보를 표시할 때 사용
 *
 * Refresh Token은 후속 이슈에서 HttpOnly Cookie로 처리할 예정
 */

public record JwtDto (
    UserDto userDto,
    String accessToken
)   {
}
