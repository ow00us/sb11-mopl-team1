package com.mopl.user.service;

import com.mopl.user.entity.User;
import java.util.Collection;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Spring Security 인증 과정에서 사용하는 현재 사용자 정보
 *
 * 기본 Spring Security User 객체와 달리 User 엔티티 정보를 보관
 * 인증 성공 후 AuthService가 사용자 정보를 다시 조회하지 않고도
 * 사용자 UUID, 역할, 프로필 정보를 활용할 수 있다.
 */
@Getter
@RequiredArgsConstructor
public class MoplUserDetails implements UserDetails {

    private final User user;

    /**
     * Spring Security가 비밀번호를 비교할 때 사용하는 BCrypt 해시
     * 외부 API 응답에는 절대 포함되지 않는다.
     */
    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    /**
     * 로그인 식별자로 사용하는 정규화된 이메일
     */
    @Override
    public String getUsername() {
        return user.getEmail();
    }

    /**
     * 사용자 역할을 Spring Security 권한 형식인 ROLE_USER 또는 ROLE_ADMIN으로 변환
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(
            new SimpleGrantedAuthority("ROLE_" + user.getRole().name())
        );
    }

    /**
     * locked 값이 true면 Spring Security가 LockedException을 발생시켜 로그인을 거부
     */
    @Override
    public boolean isAccountNonLocked() {
        return !user.isLocked();
    }
}
