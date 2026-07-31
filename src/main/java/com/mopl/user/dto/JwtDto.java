package com.mopl.user.dto;

/**
 * 로그인 성공 후 클라이언트에 반환하는 JWT 액세스 토큰 응답
 *
 * Refresh Token은 후속 이슈에서 HttpOnly Cookie로 처리할 예정이므로,
 * 현재는 accessToken만 JSON 본문으로 반환
 */

public record JwtDto (
    String accessToken
)   {

}
