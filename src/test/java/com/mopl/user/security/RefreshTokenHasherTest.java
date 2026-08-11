package com.mopl.user.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RefreshTokenHasher의 SHA-256 변환 규칙을 검증
 */
class RefreshTokenHasherTest {

    private final RefreshTokenHasher refreshTokenHasher =
        new RefreshTokenHasher();

    @Test
    @DisplayName("Refresh Token 원문을 64자의 SHA-256 해시로 변환한다")
    void hash_success() {
        // given
        String rawToken = "test";

        // when
        String tokenHash = refreshTokenHasher.hash(rawToken);

        // then
        /*
         * 문자열 "test"의 알려진 SHA-256 결과를 사용해
         * 단순히 길이만 맞는 값이 아니라 실제 SHA-256이 적용됐는지 검증
         */
        assertThat(tokenHash).isEqualTo(
            "9f86d081884c7d659a2feaa0c55ad015"
                + "a3bf4f1b2b0b822cd15d6c15b0f00a08"
        );

        assertThat(tokenHash).hasSize(64);
        assertThat(tokenHash).isNotEqualTo(rawToken);
    }

    @Test
    @DisplayName("같은 Refresh Token 원문은 항상 같은 해시로 변환한다")
    void hash_returnsSameValueForSameToken() {
        // given
        String rawToken = "same-refresh-token";

        // when
        String firstHash = refreshTokenHasher.hash(rawToken);
        String secondHash = refreshTokenHasher.hash(rawToken);

        // then
        assertThat(firstHash).isEqualTo(secondHash);
    }

    @Test
    @DisplayName("Refresh Token 원문이 null이면 변환을 거부한다")
    void hash_failWhenRawTokenIsNull() {
        assertThatThrownBy(() ->
            refreshTokenHasher.hash(null)
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Refresh Token 원문은 null일 수 없습니다.");
    }
}
