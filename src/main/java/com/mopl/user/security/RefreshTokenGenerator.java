package com.mopl.user.security;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * 사용자 정보가 포함되지 않은 Opaque Refresh Token을 생성
 *
 * Refresh Token은 내용을 해석해서 사용하는 JWT가 아니라,
 * 서버에 저장된 로그인 세션을 찾기 위한 충분히 긴 무작위 문자열
 *
 * java.util.Random은 보안 목적의 난수 생성기가 아니므로 사용하지 않는다.
 * 암호학적으로 안전한 SecureRandom을 사용하여 토큰 값을 예측하기 어렵게 만든다.
 */
@Component
public class RefreshTokenGenerator {

    /**
     * Refresh Token에 사용할 무작위 바이트 길이
     *
     * 32바이트는 256비트이며, Refresh Token을 무작위 대입으로
     * 추측하기 어렵게 만드는 충분한 엔트로피를 제공
     */
    private static final int TOKEN_BYTE_LENGTH = 32;

    /**
     * 암호학적으로 안전한 난수를 생성하는 객체
     *
     * SecureRandom은 내부적으로 다음 난수를 생성할 때 상태를 갱신하므로
     * 토큰을 생성할 때마다 새 객체를 만들 필요 없이 재사용 가능.
     */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 256비트의 무작위 Refresh Token 원문을 생성
     *
     * 생성된 32바이트를 URL-safe Base64 문자열로 변환
     * URL-safe 인코더는 일반 Base64의 '+'와 '/' 대신
     * URL과 Cookie에서 안전하게 사용할 수 있는 '-'와 '_'를 사용
     *
     * 패딩 문자 '='은 토큰 데이터 자체에 필요하지 않으므로 제거
     * 32바이트를 패딩 없는 Base64 URL 형식으로 표현하면 43자가 된다.
     *
     * @return 브라우저 Cookie에 전달할 Refresh Token 원문
     */
    public String generate() {
        byte[] randomBytes = new byte[TOKEN_BYTE_LENGTH];

        secureRandom.nextBytes(randomBytes);

        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(randomBytes);
    }
}
