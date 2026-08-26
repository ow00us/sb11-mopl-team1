package com.mopl.user.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mopl.user.config.OAuthLocalCredentialProperties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 이메일 인증 코드 HMAC 생성 정책을 검증
 */
class EmailVerificationCodeHasherTest {

    private static final UUID USER_ID =
        UUID.fromString(
            "13e831b1-a190-4513-8df4-913536676430"
        );

    private EmailVerificationCodeHasher
        verificationCodeHasher;

    @BeforeEach
    void setUp() {
        OAuthLocalCredentialProperties properties =
            new OAuthLocalCredentialProperties();

        properties.setVerificationSecret(
            "test-oauth-local-verification-secret-1234"
        );

        verificationCodeHasher =
            new EmailVerificationCodeHasher(
                properties
            );
    }

    @Test
    @DisplayName("인증 코드를 64자의 HMAC-SHA256 값으로 변환한다")
    void hash_returnsHmacSha256HexValue() {
        // when
        String codeHash =
            verificationCodeHasher.hash(
                USER_ID,
                "user@example.com",
                "123456"
            );

        // then
        assertThat(codeHash)
            .matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("같은 사용자 이메일 코드에는 같은 HMAC을 반환한다")
    void hash_returnsSameValueForSameInput() {
        // when
        String firstHash =
            verificationCodeHasher.hash(
                USER_ID,
                "user@example.com",
                "123456"
            );

        String secondHash =
            verificationCodeHasher.hash(
                USER_ID,
                "user@example.com",
                "123456"
            );

        // then
        assertThat(secondHash)
            .isEqualTo(firstHash);
    }

    @Test
    @DisplayName("인증 코드가 다르면 다른 HMAC을 반환한다")
    void hash_returnsDifferentValueForDifferentCode() {
        // when
        String firstHash =
            verificationCodeHasher.hash(
                USER_ID,
                "user@example.com",
                "123456"
            );

        String secondHash =
            verificationCodeHasher.hash(
                USER_ID,
                "user@example.com",
                "654321"
            );

        // then
        assertThat(secondHash)
            .isNotEqualTo(firstHash);
    }

    @Test
    @DisplayName("사용자가 다르면 같은 이메일과 코드에도 다른 HMAC을 반환한다")
    void hash_returnsDifferentValueForDifferentUser() {
        // when
        String firstHash =
            verificationCodeHasher.hash(
                USER_ID,
                "user@example.com",
                "123456"
            );

        String secondHash =
            verificationCodeHasher.hash(
                UUID.randomUUID(),
                "user@example.com",
                "123456"
            );

        // then
        assertThat(secondHash)
            .isNotEqualTo(firstHash);
    }

    @Test
    @DisplayName("이메일이 다르면 같은 사용자와 코드에도 다른 HMAC을 반환한다")
    void hash_returnsDifferentValueForDifferentEmail() {
        // when
        String firstHash =
            verificationCodeHasher.hash(
                USER_ID,
                "first@example.com",
                "123456"
            );

        String secondHash =
            verificationCodeHasher.hash(
                USER_ID,
                "second@example.com",
                "123456"
            );

        // then
        assertThat(secondHash)
            .isNotEqualTo(firstHash);
    }

    @Test
    @DisplayName("인증 코드가 6자리 숫자가 아니면 거부한다")
    void hash_rejectsInvalidCode() {
        assertThatThrownBy(
            () ->
                verificationCodeHasher.hash(
                    USER_ID,
                    "user@example.com",
                    "12345a"
                )
        )
            .isInstanceOf(
                IllegalArgumentException.class
            )
            .hasMessage(
                "이메일 인증 코드는 6자리 숫자여야 합니다."
            );
    }

    @Test
    @DisplayName("사용자 UUID가 null이면 거부한다")
    void hash_rejectsNullUserId() {
        assertThatThrownBy(
            () ->
                verificationCodeHasher.hash(
                    null,
                    "user@example.com",
                    "123456"
                )
        )
            .isInstanceOf(
                IllegalArgumentException.class
            );
    }

    @Test
    @DisplayName("대상 이메일이 공백이면 거부한다")
    void hash_rejectsBlankEmail() {
        assertThatThrownBy(
            () ->
                verificationCodeHasher.hash(
                    USER_ID,
                    " ",
                    "123456"
                )
        )
            .isInstanceOf(
                IllegalArgumentException.class
            );
    }
}
