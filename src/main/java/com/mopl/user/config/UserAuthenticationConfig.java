package com.mopl.user.config;

import com.mopl.user.service.MoplUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authentication.ProviderManager;

// user 도메인의 회원가입과 로그인에 필요한 인증 관련 Bean을 설정
@Configuration
public class UserAuthenticationConfig {

    // 비밀번호 원문을 BCrypt 해시로 변환하고 검증하는 도구를 Spring Bean으로 등록
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 이메일·비밀번호 기반 인증을 처리하는 AuthenticationManager를 등록
     *
     * DaoAuthenticationProvider가 이메일로 사용자를 조회하고
     * PasswordEncoder로 입력 비밀번호와 저장된 해시를 비교
     */
    @Bean
    public AuthenticationManager authenticationManager(
        MoplUserDetailsService userDetailsService,
        PasswordEncoder passwordEncoder
    ) {
        DaoAuthenticationProvider authenticationProvider =
            new DaoAuthenticationProvider(passwordEncoder);

        // 이메일로 사용자를 조회하는 로직을 인증 Provider에 연결
        authenticationProvider.setUserDetailsService(userDetailsService);

        return new ProviderManager(authenticationProvider);
    }
}
