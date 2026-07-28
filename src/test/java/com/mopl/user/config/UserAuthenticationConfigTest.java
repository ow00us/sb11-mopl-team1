package com.mopl.user.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mopl.user.service.MoplUserDetailsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
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

    @Test
    @DisplayName("이메일과 비밀번호가 일치하면 인증에 성공한다")
    void authenticationManager_authenticateSuccess() {
        UserAuthenticationConfig config = new UserAuthenticationConfig();
        PasswordEncoder passwordEncoder = config.passwordEncoder();

        MoplUserDetailsService userDetailsService = mock(MoplUserDetailsService.class);

        String rawPassword = "passwordTest1!";
        String encodedPassword = passwordEncoder.encode(rawPassword);

        UserDetails userDetails =
            org.springframework.security.core.userdetails.User.builder()
                .username("user@example.com")
                .password(encodedPassword)
                .authorities("ROLE_USER")
                .build();

        when(userDetailsService.loadUserByUsername("user@example.com"))
            .thenReturn(userDetails);

        AuthenticationManager authenticationManager =
            config.authenticationManager(userDetailsService, passwordEncoder);

        Authentication authentication = authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated(
                "user@example.com",
                rawPassword
            )
        );

        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getName()).isEqualTo("user@example.com");
    }

    @Test
    @DisplayName("비밀번호가 일치하지 않으면 인증에 실패한다")
    void authenticationManager_failWhenPasswordDoesNotMatch() {
        UserAuthenticationConfig config = new UserAuthenticationConfig();
        PasswordEncoder passwordEncoder = config.passwordEncoder();

        MoplUserDetailsService userDetailsService = mock(MoplUserDetailsService.class);

        UserDetails userDetails =
            org.springframework.security.core.userdetails.User.builder()
                .username("user@example.com")
                .password(passwordEncoder.encode("passwordTest1!"))
                .authorities("ROLE_USER")
                .build();

        when(userDetailsService.loadUserByUsername("user@example.com"))
            .thenReturn(userDetails);

        AuthenticationManager authenticationManager =
            config.authenticationManager(userDetailsService, passwordEncoder);

        assertThatThrownBy(() -> authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated(
                "user@example.com",
                "wrongPassword1!"
            )
        ))
            .isInstanceOf(BadCredentialsException.class);
    }
}
