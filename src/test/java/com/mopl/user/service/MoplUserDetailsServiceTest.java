package com.mopl.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

// 이메일 기반 로그인 사용자를 조회하는 로직을 검증

@ExtendWith(MockitoExtension.class)
class MoplUserDetailsServiceTest {

    @Mock
    UserRepository userRepository;

    @InjectMocks
    MoplUserDetailsService moplUserDetailsService;

    @Test
    @DisplayName("정규화한 이메일로 사용자를 조회해 Spring Security 사용자 정보로 변환한다")
    void loadUserByUsername_success() {
        // given
        User user = User.builder()
            .email("user@example.com")
            .passwordHash("encoded-password")
            .name("테스트 사용자")
            .role(UserRole.USER)
            .locked(false)
            .build();

        when(userRepository.findByEmail("user@example.com"))
            .thenReturn(Optional.of(user));

        // when
        UserDetails userDetails =
            moplUserDetailsService.loadUserByUsername(" User@Example.Com ");

        // then
        assertThat(userDetails).isInstanceOf(MoplUserDetails.class);

        MoplUserDetails moplUserDetails = (MoplUserDetails) userDetails;

        /*
         * 조회한 User가 커스텀 principal에 보관되는지 확인합니다.
         * AuthService는 이 User를 사용하므로 다시 DB를 조회할 필요가 없습니다.
         */
        assertThat(moplUserDetails.getUser()).isSameAs(user);
        assertThat(moplUserDetails.getUsername())
            .isEqualTo("user@example.com");
        assertThat(moplUserDetails.getPassword())
            .isEqualTo("encoded-password");
        assertThat(moplUserDetails.isAccountNonLocked()).isTrue();
        assertThat(moplUserDetails.getAuthorities())
            .extracting("authority")
            .containsExactly("ROLE_USER");

        verify(userRepository).findByEmail("user@example.com");
    }

    @Test
    @DisplayName("등록되지 않은 이메일이면 인증 사용자 조회에 실패한다")
    void loadUserByUsername_fail_whenUserDoesNotExist() {
        // given
        when(userRepository.findByEmail("user@example.com"))
            .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
            moplUserDetailsService.loadUserByUsername("user@example.com")
        )
            .isInstanceOf(UsernameNotFoundException.class);

        verify(userRepository).findByEmail("user@example.com");
    }

    @Test
    @DisplayName("잠긴 사용자는 잠긴 인증 사용자 정보로 변환한다")
    void loadUserByUsername_lockedUser() {
        // given
        User user = User.builder()
            .email("locked@example.com")
            .passwordHash("encoded-password")
            .name("잠긴 사용자")
            .role(UserRole.USER)
            .locked(true)
            .build();

        when(userRepository.findByEmail("locked@example.com"))
            .thenReturn(Optional.of(user));

        // when
        UserDetails userDetails =
            moplUserDetailsService.loadUserByUsername("locked@example.com");

        // then
        // 이후 Spring Security 인증 과정에서 잠긴 계정을 거부할 수 있도록 상태를 전달
        assertThat(userDetails.isAccountNonLocked()).isFalse();
    }
}
