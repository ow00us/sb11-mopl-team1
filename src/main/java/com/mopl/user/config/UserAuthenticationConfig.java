package com.mopl.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;


// user 도메인의 회원가입과 로그인에 필요한 인증 관련 Bean을 설정
@Configuration
public class UserAuthenticationConfig {

    // 비밀번호 원문을 BCrypt 해시로 변환하고 검증하는 도구를 Spring Bean으로 등록합니다.
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
