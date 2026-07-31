package com.mopl.user.service;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.security.JwtProvider;
import com.mopl.user.dto.JwtDto;
import com.mopl.user.dto.SignInRequest;
import com.mopl.user.entity.User;
import com.mopl.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인 API의 인증 및 JWT 발급 흐름을 담당하는 서비스
 *
 * 실제 이메일·비밀번호 대조는 Spring Security의 AuthenticationManager에 맡기고,
 * 인증 성공 후에는 사용자 ID와 역할을 조회하여 JWT 액세스 토큰을 발급
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;

    /**
     * 이메일과 비밀번호로 사용자를 인증하고 JWT 액세스 토큰을 발급
     *
     * @param request 로그인 요청 데이터
     * @return 발급된 액세스 토큰
     * @throws BusinessException 인증 정보가 올바르지 않거나 계정이 잠긴 경우
     */
    public JwtDto signIn(SignInRequest request) {
        Authentication authentication;

        try {
            /*
             * AuthenticationManager는 이전 로그인 기반 구현에서 등록한
             * DaoAuthenticationProvider를 사용
             *
             * 내부적으로 MoplUserDetailsService가 이메일로 사용자를 조회하고,
             * PasswordEncoder(BCrypt)가 입력 비밀번호와 저장된 해시를 비교
             */
            authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(
                    request.email(),
                    request.password()
                )
            );
        } catch (AuthenticationException exception) {
            /*
             * 이메일 미등록, 비밀번호 불일치, 계정 잠김 상황을 하나의 401 응답으로 처리
             * 어떤 값이 틀렸는지 구체적으로 알려주지 않아 계정 존재 여부 노출을 막음
             */
            throw new BusinessException(
                ErrorCode.UNAUTHORIZED,
                "이메일 또는 비밀번호가 올바르지 않습니다."
            );
        }

        /*
         * authentication.getName()은 인증에 성공한 UserDetails의 username,
         * 즉 MoplUserDetailsService가 정규화한 이메일
         *
         * JWT에는 사용자 UUID와 역할이 필요하므로, 인증 완료 후 사용자를 다시 조회
         */
        User user = userRepository.findByEmail(authentication.getName())
            .orElseThrow(() -> new BusinessException(
                ErrorCode.UNAUTHORIZED,
                "이메일 또는 비밀번호가 올바르지 않습니다."
            ));

        String accessToken = jwtProvider.createAccessToken(
            user.getId(),
            user.getRole().name()
        );

        return new JwtDto(accessToken);
    }
}
