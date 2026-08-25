package com.mopl.user.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 이메일 인증 코드 생성 규칙을 검증
 */
class EmailVerificationCodeGeneratorTest {

    private final EmailVerificationCodeGenerator
        verificationCodeGenerator =
        new EmailVerificationCodeGenerator();

    @Test
    @DisplayName("인증 코드는 항상 6자리 숫자로 생성된다")
    void generate_returnsSixDigitNumericCode() {
        /*
         * SecureRandom 결과와 관계없이 모든 생성 결과가
         * 고정된 형식을 만족하는지 반복해서 확인
         */
        for (
            int attempt = 0;
            attempt < 1_000;
            attempt++
        ) {
            String verificationCode =
                verificationCodeGenerator.generate();

            assertThat(verificationCode)
                .matches("^\\d{6}$");
        }
    }

    @Test
    @DisplayName("인증 코드는 호출할 때마다 무작위 값으로 생성된다")
    void generate_returnsDifferentCodes() {
        // given
        Set<String> generatedCodes =
            new HashSet<>();

        // when
        for (
            int attempt = 0;
            attempt < 100;
            attempt++
        ) {
            generatedCodes.add(
                verificationCodeGenerator.generate()
            );
        }

        // then
        /*
         * SecureRandom이 실제 난수 생성을 수행하는지 확인
         * 100회 모두 같은 값일 확률은 현실적으로 무시할 수 있다.
         */
        assertThat(generatedCodes.size())
            .isGreaterThan(1);
    }
}
