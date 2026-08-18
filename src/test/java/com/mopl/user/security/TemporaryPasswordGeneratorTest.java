package com.mopl.user.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TemporaryPasswordGenerator의 임시 비밀번호 생성 정책을 검증
 */
class TemporaryPasswordGeneratorTest {

    private final TemporaryPasswordGenerator
        temporaryPasswordGenerator =
        new TemporaryPasswordGenerator();

    @Test
    @DisplayName("비밀번호 정책을 만족하는 16자 임시 비밀번호를 생성한다")
    void generate_success() {
        // when
        String temporaryPassword =
            temporaryPasswordGenerator.generate();

        // then
        assertThat(temporaryPassword)
            .hasSize(16);

        /*
         * 대문자, 소문자, 숫자와 특수문자를 각각 하나 이상 포함하고
         * 정의한 ASCII 문자만 사용하는지 검증
         */
        assertThat(temporaryPassword)
            .matches(
                "^(?=.*[A-Z])"
                    + "(?=.*[a-z])"
                    + "(?=.*\\d)"
                    + "(?=.*[!@#$%^&*])"
                    + "[A-Za-z\\d!@#$%^&*]{16}$"
            );
    }

    @Test
    @DisplayName("직접 입력할 때 혼동하기 쉬운 문자를 생성하지 않는다")
    void generate_doesNotContainAmbiguousCharacters() {
        // when
        /*
         * 한 번의 생성 결과만 확인하면 특정 문자가 우연히 나오지 않았을
         * 수 있으므로 여러 번 생성하여 허용 문자 정책을 확인
         */
        for (
            int index = 0;
            index < 100;
            index++
        ) {
            String temporaryPassword =
                temporaryPasswordGenerator
                    .generate();

            // then
            assertThat(temporaryPassword)
                .doesNotContain(
                    "I",
                    "O",
                    "l",
                    "0",
                    "1"
                );
        }
    }

    @Test
    @DisplayName("발급 요청마다 서로 다른 임시 비밀번호를 생성한다")
    void generate_returnsDifferentPasswords() {
        // given
        Set<String> temporaryPasswords =
            new HashSet<>();

        // when
        for (
            int index = 0;
            index < 100;
            index++
        ) {
            temporaryPasswords.add(
                temporaryPasswordGenerator
                    .generate()
            );
        }

        // then
        /*
         * 100개의 결과가 모두 Set에 남아 있다면 테스트 범위에서는
         * 중복된 임시 비밀번호가 생성되지 않았다는 의미
         */
        assertThat(temporaryPasswords)
            .hasSize(100);
    }
}
