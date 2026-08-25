package com.mopl.user.dto;

/**
 * OAuth 계정 연결 인증을 시작할 서버 경로
 *
 * @param authorizationPath Spring Security OAuth2 인증 시작 경로
 */
public record OAuthLinkStartResponse(
    String authorizationPath
) {
}
