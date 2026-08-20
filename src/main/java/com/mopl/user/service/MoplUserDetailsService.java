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

        /*
         * OAuth로만 가입한 사용자는 로컬 비밀번호가 없으므로
         * 이메일·비밀번호 인증을 사용할 수 없다.
         *
         * null이나 비어 있는 비밀번호 해시를 UserDetails로 전달하면
         * PasswordEncoder 구현에 따라 예외 또는 불필요한 경고가 발생할 수 있다.
         *
         * UsernameNotFoundException을 사용하면 Spring Security가
         * 존재하지 않는 사용자와 동일한 BadCredentialsException으로 변환하므로
         * 외부 응답만으로 계정 가입 방식도 구분할 수 없다.
         */
        if (user.getPasswordHash() == null
            || user.getPasswordHash().isBlank()) {
            throw new UsernameNotFoundException(
                "이메일·비밀번호 로그인을 사용할 수 없는 계정입니다."
            );
        }

        return new MoplUserDetails(user);
    }
}
