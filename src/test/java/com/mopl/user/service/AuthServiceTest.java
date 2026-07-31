package com.mopl.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.security.JwtProvider;
import com.mopl.user.dto.JwtDto;
import com.mopl.user.dto.SignInRequest;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 로그인 API 서비스의 인증 및 JWT 발급 규칙을 검증
 *
 * AuthenticationManager와 JwtProvider는 외부 의존성이므로 Mock으로 대체하고,
 * AuthService가 각 결과를 올바르게 연결하는지에 집중
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final UUID USER_ID =
        UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    AuthenticationManager authenticationManager;

    @Mock
    UserRepository userRepository;

    @Mock
    JwtProvider jwtProvider;

    @InjectMocks
    AuthService authService;

    @Test
    @DisplayName("올바른 이메일과 비밀번호면 JWT 액세스 토큰을 발급한다")
    void signIn_success() {
        // given
        SignInRequest request = new SignInRequest(
            "User@Example.Com",
            "passwordTest1!"
        );

        /*
         * AuthenticationManager 인증 성공 결과
         * 실제 서비스에서는 MoplUserDetailsService가 이메일을 소문자로 정규화하므로,
         * 성공한 Authentication의 name도 정규화된 이메일이라고 가정
         */
        Authentication authenticated = UsernamePasswordAuthenticationToken.authenticated(
            "user@example.com",
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );

        User user = User.builder()
            .email("user@example.com")
            .passwordHash("encoded-password")
            .name("테스트 사용자")
            .role(UserRole.USER)
            .locked(false)
            .build();

        /*
         * JPA가 저장 시 생성하는 ID를 단위 테스트에서는 직접 넣는다.
         * JWT 발급 인자로 사용자 UUID가 전달되는지 확인하기 위함
         */
        ReflectionTestUtils.setField(user, "id", USER_ID);

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
            .thenReturn(authenticated);
        when(userRepository.findByEmail("user@example.com"))
            .thenReturn(Optional.of(user));
        when(jwtProvider.createAccessToken(USER_ID, "USER"))
            .thenReturn("access-token");

        // when
        JwtDto response = authService.signIn(request);

        // then
        assertThat(response.accessToken()).isEqualTo("access-token");

        /*
         * 요청으로 받은 이메일·비밀번호가 AuthenticationManager에 전달됐는지 확인
         * 이 객체는 아직 인증 전 상태의 UsernamePasswordAuthenticationToken
         */
        ArgumentCaptor<UsernamePasswordAuthenticationToken> tokenCaptor =
            ArgumentCaptor.forClass(UsernamePasswordAuthenticationToken.class);

        verify(authenticationManager).authenticate(tokenCaptor.capture());

        UsernamePasswordAuthenticationToken authenticationToken =
            tokenCaptor.getValue();

        assertThat(authenticationToken.getPrincipal()).isEqualTo("User@Example.Com");
        assertThat(authenticationToken.getCredentials()).isEqualTo("passwordTest1!");

        verify(userRepository).findByEmail("user@example.com");
        verify(jwtProvider).createAccessToken(USER_ID, "USER");
    }

    @Test
    @DisplayName("이메일 또는 비밀번호가 올바르지 않으면 인증 실패로 처리한다")
    void signIn_fail_whenCredentialsDoNotMatch() {
        // given
        SignInRequest request = new SignInRequest(
            "user@example.com",
            "wrongPassword1!"
        );

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
            .thenThrow(new BadCredentialsException("인증 실패"));

        // when & then
        assertThatThrownBy(() -> authService.signIn(request))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.UNAUTHORIZED);

        /*
         * 인증 실패 후에는 사용자 정보를 조회하거나 JWT를 발급하면 안 됨
         */
        verifyNoInteractions(userRepository, jwtProvider);
    }

    @Test
    @DisplayName("잠긴 계정이면 인증 실패로 처리하고 JWT를 발급하지 않는다")
    void signIn_fail_whenAccountIsLocked() {
        // given
        SignInRequest request = new SignInRequest(
            "locked@example.com",
            "passwordTest1!"
        );

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
            .thenThrow(new LockedException("잠긴 계정"));

        // when & then
        assertThatThrownBy(() -> authService.signIn(request))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.UNAUTHORIZED);

        verifyNoInteractions(userRepository, jwtProvider);
    }
}
