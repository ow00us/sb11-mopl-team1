package com.mopl.user.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RefreshTokenGenerator의 Opaque Token 생성 규칙을 검증
 */
class RefreshTokenGeneratorTest {

    private final RefreshTokenGenerator refreshTokenGenerator =
        new RefreshTokenGenerator();

    @Test
    @DisplayName("256비트의 URL-safe Refresh Token을 생성한다")
    void generate_success() {
        // when
        String token = refreshTokenGenerator.generate();

        // then
        /*
         * 32바이트를 패딩 없는 Base64 URL 형식으로 변환하면
         * 결과 문자열의 길이는 43자가 된다.
         */
        assertThat(token).hasSize(43);

        /*
         * Base64 URL-safe 인코딩은 영문 대소문자, 숫자,
         * 하이픈(-), 밑줄(_)만 포함
         */
        assertThat(token)
            .matches("^[A-Za-z0-9_-]{43}$");
    }

    @Test
    @DisplayName("발급 요청마다 서로 다른 Refresh Token을 생성한다")
    void generate_returnsDifferentTokens() {
        // given
        Set<String> tokens = new HashSet<>();

        // when
        for (int index = 0; index < 100; index++) {
            tokens.add(refreshTokenGenerator.generate());
        }

        // then
        /*
         * 100번의 발급 결과가 모두 Set에 저장되어 있다면
         * 중복된 토큰이 생성되지 않았다는 의미이다.
         */
        assertThat(tokens).hasSize(100);
    }
}
