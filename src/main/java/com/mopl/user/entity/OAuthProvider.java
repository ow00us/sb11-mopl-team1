package com.mopl.user.entity;

/**
 * 서비스에서 지원하는 OAuth 인증 제공자를 구분
 *
 * <p>OAuth 사용자는 이메일 주소만으로 식별하지 않습니다.
 * 이메일은 사용자가 변경할 수 있고, 서로 다른 Provider가 같은 이메일을
 * 전달할 수도 있기 때문입니다.</p>
 *
 * <p>실제 OAuth 계정은 {@code provider + providerUserId} 조합으로
 * 식별하며, 이 enum은 그 조합에서 Provider 부분을 표현합니다.</p>
 *
 * <p>데이터베이스에는 enum 이름을 문자열로 저장합니다.
 * enum 선언 순서에 의존하는 숫자 저장 방식을 사용하지 않으므로
 * 추후 Provider가 추가되더라도 기존 데이터의 의미가 바뀌지 않습니다.</p>
 */
public enum OAuthProvider {

    /**
     * Google OAuth 2.0 Provider
     */
    GOOGLE,

    /**
     * Kakao OAuth 2.0 Provider
     */
    KAKAO,

    /**
     * Naver OAuth 2.0 Provider
     */
    NAVER
}
