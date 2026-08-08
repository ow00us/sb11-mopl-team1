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
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
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

    @Test
    @DisplayName("등록되지 않은 이메일이면 인증에 실패한다")
    void authenticationManager_failWhenUserIsNotFound() {
        // given
        UserAuthenticationConfig config = new UserAuthenticationConfig();
        PasswordEncoder passwordEncoder = config.passwordEncoder();

        MoplUserDetailsService userDetailsService = mock(MoplUserDetailsService.class);

        // UserDetailsService가 사용자를 찾지 못하면 Spring Security는
        // 계정 존재 여부 노출 방지를 위해 BadCredentialsException으로 변환합니다.
        when(userDetailsService.loadUserByUsername("unknown@example.com"))
            .thenThrow(new UsernameNotFoundException("등록되지 않은 이메일입니다."));

        AuthenticationManager authenticationManager =
            config.authenticationManager(userDetailsService, passwordEncoder);

        // when & then
        assertThatThrownBy(() -> authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated(
                "unknown@example.com",
                "passwordTest1!"
            )
        ))
            .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("잠긴 계정이면 올바른 비밀번호를 입력해도 인증에 실패한다")
    void authenticationManager_failWhenAccountIsLocked() {
        // given
        UserAuthenticationConfig config = new UserAuthenticationConfig();
        PasswordEncoder passwordEncoder = config.passwordEncoder();

        MoplUserDetailsService userDetailsService = mock(MoplUserDetailsService.class);

        UserDetails lockedUser =
            org.springframework.security.core.userdetails.User.builder()
                .username("user@example.com")
                .password(passwordEncoder.encode("passwordTest1!"))
                .authorities("ROLE_USER")
                .accountLocked(true)
                .build();

        when(userDetailsService.loadUserByUsername("user@example.com"))
            .thenReturn(lockedUser);

        AuthenticationManager authenticationManager =
            config.authenticationManager(userDetailsService, passwordEncoder);

        // when & then
        assertThatThrownBy(() -> authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated(
                "user@example.com",
                "passwordTest1!"
            )
        ))
            .isInstanceOf(LockedException.class);
    }
}
