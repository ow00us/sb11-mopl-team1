package com.mopl.user.service;

import com.mopl.user.entity.User;
import com.mopl.user.repository.UserRepository;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 로그인에 사용할 이메일로 사용자를 조회해 Spring Security 인증 정보로 변환
 */
@Service
@RequiredArgsConstructor
public class MoplUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    /**
     * Spring Security가 로그인 식별자를 전달하면 사용자 정보를 조회
     *
     * 현재 서비스의 로그인 식별자는 username이라는 이름의 이메일
     */
    @Override
    public UserDetails loadUserByUsername(String username)
        throws UsernameNotFoundException {

        // 회원가입 때와 같은 규칙으로 이메일을 정규화해 조회 기준을 통일
        String normalizedEmail = username.strip().toLowerCase(Locale.ROOT);

        User user = userRepository.findByEmail(normalizedEmail)
            .orElseThrow(() -> new UsernameNotFoundException(
                "등록되지 않은 이메일입니다."
            ));

        return new MoplUserDetails(user);
    }
}
