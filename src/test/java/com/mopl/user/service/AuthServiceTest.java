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
import java.time.Instant;
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
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 로그인 API 서비스의 인증 및 JWT 발급 규칙을 검증
 *
 * AuthenticationManager와 JwtProvider는 Mock으로 대체하고
 * AuthService가 각 결과를 올바르게 연결하는지 확인
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final UUID USER_ID =
        UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final Instant CREATED_AT =
        Instant.parse("2026-07-31T03:00:00Z");

    @Mock
    AuthenticationManager authenticationManager;

    @Mock
    JwtProvider jwtProvider;

    @InjectMocks
    AuthService authService;

    @Test
    @DisplayName("로그인 성공 시 사용자 정보와 JWT 액세스 토큰을 반환한다")
    void signIn_success() {
        // given
        SignInRequest request = new SignInRequest(
            "User@Example.Com",
            "passwordTest1!"
        );

        User user = User.builder()
            .email("user@example.com")
            .passwordHash("encoded-password")
            .name("테스트 사용자")
            .profileImageUrl("https://example.com/profile.png")
            .role(UserRole.USER)
            .locked(false)
            .build();

        /*
         * 실제 저장 과정에서는 JPA가 UUID와 생성 시각을 채웁니다.
         * 단위 테스트에서는 로그인 응답을 검증하기 위해 직접 설정합니다.
         */
        ReflectionTestUtils.setField(user, "id", USER_ID);
        ReflectionTestUtils.setField(user, "createdAt", CREATED_AT);

        MoplUserDetails principal = new MoplUserDetails(user);

        Authentication authenticated =
            UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                principal.getAuthorities()
            );

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
            .thenReturn(authenticated);

        when(jwtProvider.createAccessToken(USER_ID, "USER"))
            .thenReturn("access-token");

        // when
        JwtDto response = authService.signIn(request);

        // then
        assertThat(response.accessToken()).isEqualTo("access-token");

        assertThat(response.userDto().id()).isEqualTo(USER_ID);
        assertThat(response.userDto().createdAt()).isEqualTo(CREATED_AT);
        assertThat(response.userDto().email()).isEqualTo("user@example.com");
        assertThat(response.userDto().name()).isEqualTo("테스트 사용자");
        assertThat(response.userDto().profileImageUrl())
            .isEqualTo("https://example.com/profile.png");
        assertThat(response.userDto().role()).isEqualTo(UserRole.USER);
        assertThat(response.userDto().locked()).isFalse();

        /*
         * 클라이언트가 보낸 이메일과 비밀번호가 인증 객체에
         * 정확하게 담겨 AuthenticationManager로 전달됐는지 확인합니다.
         */
        ArgumentCaptor<UsernamePasswordAuthenticationToken> tokenCaptor =
            ArgumentCaptor.forClass(UsernamePasswordAuthenticationToken.class);

        verify(authenticationManager).authenticate(tokenCaptor.capture());

        UsernamePasswordAuthenticationToken authenticationToken =
            tokenCaptor.getValue();

        assertThat(authenticationToken.getPrincipal()).isEqualTo("User@Example.Com");
        assertThat(authenticationToken.getCredentials()).isEqualTo("passwordTest1!");

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
        verifyNoInteractions(jwtProvider);
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

        verifyNoInteractions(jwtProvider);
    }
}
