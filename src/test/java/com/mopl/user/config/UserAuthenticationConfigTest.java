package com.mopl.user.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;


// user 도메인 인증 설정의 비밀번호 해시 기능을 검증합니다.
class UserAuthenticationConfigTest {

    @Test
    @DisplayName("비밀번호를 해시하고 동일한 비밀번호를 검증할 수 있다")
    void passwordEncoder_encodeAndMatches() {
        UserAuthenticationConfig config = new UserAuthenticationConfig();

        PasswordEncoder passwordEncoder = config.passwordEncoder();

        String rawPassword = "passwordTest1!";
        String encodedPassword = passwordEncoder.encode(rawPassword);

        // DB에는 원문이 아닌 해시 값이 저장
        assertThat(encodedPassword).isNotEqualTo(rawPassword);

        // 같은 비밀번호만 검증에 성공
        assertThat(passwordEncoder.matches(rawPassword, encodedPassword)).isTrue();
        assertThat(passwordEncoder.matches("wrongPassword1!", encodedPassword)).isFalse();
    }
}
