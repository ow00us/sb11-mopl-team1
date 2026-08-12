package com.mopl.user.service;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.security.JwtProvider;
import com.mopl.user.dto.JwtDto;
import com.mopl.user.dto.UserDto;
import com.mopl.user.dto.SignInRequest;
import com.mopl.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

/**
 * 로그인 API의 인증 및 JWT 발급 흐름을 담당하는 서비스
 *
 * 실제 이메일·비밀번호 대조는 Spring Security의 AuthenticationManager에 위임
 * 인증 성공 후에는 MoplUserDetails가 보관한 사용자 정보로 JWT와 UserDto 생성
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtProvider jwtProvider;

    /**
     * 로그인에 성공한 사용자에게 Refresh Token을 발급하고
     * Redis에 서버 세션을 저장하는 Service
     */
    private final RefreshTokenService refreshTokenService;

    /**
     * 이메일과 비밀번호로 사용자를 인증하고 로그인 결과를 반환
     *
     * @param request 로그인 요청 데이터
     * @return 인증된 사용자 정보, Access Token과 Refresh Token 발급 결과
     * @throws BusinessException 인증 정보가 올바르지 않거나 계정이 잠긴 경우
     */
    public SignInResult signIn(SignInRequest request) {
        Authentication authentication;

        try {
            /*
             * AuthenticationManager는 DaoAuthenticationProvider를 사용
             *
             * 내부적으로 MoplUserDetailsService가 이메일로 사용자를 조회하고,
             * PasswordEncoder가 입력 비밀번호와 저장된 BCrypt 해시를 비교
             */
            authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(
                    request.email(),
                    request.password()
                )
            );
        } catch (AuthenticationException exception) {
            /*
             * 클라이언트에는 실패 원인과 관계없이 동일한 401 응답을 반환
             *
             * 서버 로그에는 운영 중 원인을 구분할 수 있도록 예외 타입 남김
             * 이메일은 개인정보 노출을 줄이기 위해 일부만 표시
             * 비밀번호와 예외 메시지는 로그에 기록하지 않음
             */
            log.warn(
                "Sign-in failed. email={}, reason={}",
                maskEmail(request.email()),
                exception.getClass().getSimpleName()
            );

            throw new BusinessException(
                ErrorCode.UNAUTHORIZED,
                "이메일 또는 비밀번호가 올바르지 않습니다."
            );
        }

        /*
         * MoplUserDetailsService가 조회한 User가 인증 성공 결과의 principal에 들어 있으므로
         * 여기서는 UserRepository를 다시 호출할 필요가 없음.
         */
        MoplUserDetails principal = (MoplUserDetails) authentication.getPrincipal();

        User user = principal.getUser();

        String accessToken = jwtProvider.createAccessToken(
            user.getId(),
            user.getRole().name()
        );

        /*
         * Access Token 생성이 성공한 뒤 Refresh Token을 발급
         *
         * RefreshTokenService.issue()는 Refresh Token 원문을 생성하고
         * SHA-256 해시를 Redis에 TTL과 함께 저장
         *
         * Redis 저장에 실패하면 예외가 전파되므로 Controller가 로그인 성공 응답이나
         * 저장되지 않은 Refresh Token Cookie를 반환하지 않는다.
         */
        IssuedRefreshToken issuedRefreshToken =
            refreshTokenService.issue(user.getId());

        JwtDto jwtDto =
            new JwtDto(
                UserDto.from(user),
                accessToken
            );

        return new SignInResult(
            jwtDto,
            issuedRefreshToken
        );
    }

    /**
     * 인증 실패 로그에 사용할 이메일 마스킹 값을 만든다.
     *
     * 예: user@example.com -> u***@example.com
     *
     * 이메일 형식을 알아볼 수 없는 값은 원문을 남기지 않고 "***"로 처리
     */
    private String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "***";
        }

        String strippedEmail = email.strip();
        int atIndex = strippedEmail.indexOf('@');

        if (atIndex <= 0 || atIndex == strippedEmail.length() - 1) {
            return "***";
        }

        return strippedEmail.charAt(0) + "***" + strippedEmail.substring(atIndex);
    }
}
