package com.mopl.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.user.config.SeedAdminProperties;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Seed 관리자 계정 생성 규칙을 검증합니다.
 *
 * <p>실제 데이터베이스를 사용하지 않는 Mockito 단위 테스트로,
 * 이메일 정규화, 비밀번호 해시, 관리자 권한과 기존 계정 처리 정책을
 * 독립적으로 확인합니다.</p>
 */
@ExtendWith(MockitoExtension.class)
class SeedAdminServiceTest {

    /**
     * 초기 관리자 환경설정 Mock
     */
    @Mock
    SeedAdminProperties seedAdminProperties;

    /**
     * 사용자 조회와 저장을 담당하는 Repository Mock
     */
    @Mock
    UserRepository userRepository;

    /**
     * 비밀번호 인코딩 결과를 제어하기 위한 Encoder Mock
     */
    @Mock
    PasswordEncoder passwordEncoder;

    /**
     * 위 Mock들을 생성자에 주입해 테스트할 Service를 생성합니다.
     */
    @InjectMocks
    SeedAdminService seedAdminService;

    /**
     * Repository에 전달된 User 엔티티를 캡처해
     * 저장 필드 값을 검증합니다.
     */
    @Captor
    ArgumentCaptor<User> userCaptor;

    @Test
    @DisplayName("Seed 관리자 서비스는 seed이면서 prod가 아닐 때만 등록된다")
    void serviceIsRegisteredOnlyInNonProductionSeedProfile() {
        Profile profile =
            SeedAdminService.class
                .getAnnotation(Profile.class);

        assertThat(profile)
            .isNotNull();

        assertThat(profile.value())
            .containsExactly("seed & !prod");
    }

    @Test
    @DisplayName("관리자 계정이 없으면 이메일을 정규화하고 ADMIN 계정을 생성한다")
    void initializeAdmin_createsAdminWhenAccountDoesNotExist() {
        // given
        /*
         * 환경변수 입력에 대문자와 앞뒤 공백이 포함된 상황을 재현합니다.
         */
        when(seedAdminProperties.getEmail())
            .thenReturn("  ADMIN@EXAMPLE.COM  ");

        when(seedAdminProperties.getPassword())
            .thenReturn("SeedAdmin1!");

        when(seedAdminProperties.getName())
            .thenReturn("시연 관리자");

        /*
         * 정규화된 이메일의 기존 사용자가 없는 상황입니다.
         */
        when(
            userRepository.findByEmail(
                "admin@example.com"
            )
        ).thenReturn(Optional.empty());

        when(
            passwordEncoder.encode(
                "SeedAdmin1!"
            )
        ).thenReturn("encoded-seed-password");

        /*
         * Repository Mock은 전달받은 엔티티를 그대로 반환하도록 구성합니다.
         */
        when(
            userRepository.saveAndFlush(
                userCaptor.capture()
            )
        ).thenAnswer(invocation ->
            invocation.getArgument(0)
        );

        // when
        boolean created =
            seedAdminService.initializeAdmin();

        // then
        assertThat(created).isTrue();

        verify(userRepository)
            .findByEmail("admin@example.com");

        verify(passwordEncoder)
            .encode("SeedAdmin1!");

        User savedAdmin =
            userCaptor.getValue();

        /*
         * 이메일은 기존 회원가입 정책과 동일하게
         * 앞뒤 공백 제거 및 소문자 변환이 적용돼야 합니다.
         */
        assertThat(savedAdmin.getEmail())
            .isEqualTo("admin@example.com");

        /*
         * 비밀번호 원문이 아니라 PasswordEncoder의 결과만
         * User 엔티티에 저장돼야 합니다.
         */
        assertThat(savedAdmin.getPasswordHash())
            .isEqualTo("encoded-seed-password")
            .isNotEqualTo("SeedAdmin1!");

        assertThat(savedAdmin.getName())
            .isEqualTo("시연 관리자");

        assertThat(savedAdmin.getRole())
            .isEqualTo(UserRole.ADMIN);

        assertThat(savedAdmin.isLocked())
            .isFalse();

        assertThat(savedAdmin.getProfileImageUrl())
            .isNull();

        verify(userRepository)
            .saveAndFlush(savedAdmin);
    }

    @Test
    @DisplayName("동일 이메일의 ADMIN이 이미 존재하면 중복 생성하지 않는다")
    void initializeAdmin_doesNothingWhenAdminAlreadyExists() {
        // given
        when(seedAdminProperties.getEmail())
            .thenReturn("ADMIN@example.com");

        User existingAdmin =
            User.builder()
                .email("admin@example.com")
                .passwordHash("existing-password-hash")
                .name("기존 관리자")
                .role(UserRole.ADMIN)
                .locked(false)
                .build();

        when(
            userRepository.findByEmail(
                "admin@example.com"
            )
        ).thenReturn(
            Optional.of(existingAdmin)
        );

        // when
        boolean created =
            seedAdminService.initializeAdmin();

        // then
        assertThat(created).isFalse();

        verify(userRepository)
            .findByEmail("admin@example.com");

        /*
         * 이미 관리자가 존재하면 비밀번호를 다시 인코딩하거나
         * 기존 관리자 정보를 덮어쓰면 안 됩니다.
         */
        verifyNoInteractions(passwordEncoder);

        verify(
            userRepository,
            never()
        ).saveAndFlush(
            org.mockito.ArgumentMatchers.any(User.class)
        );

        assertThat(existingAdmin.getPasswordHash())
            .isEqualTo("existing-password-hash");

        assertThat(existingAdmin.getName())
            .isEqualTo("기존 관리자");

        assertThat(existingAdmin.getRole())
            .isEqualTo(UserRole.ADMIN);
    }

    @Test
    @DisplayName("동일 이메일의 USER가 존재하면 자동 승격하지 않고 실패한다")
    void initializeAdmin_failsWhenUserWithSameEmailExists() {
        // given
        when(seedAdminProperties.getEmail())
            .thenReturn("user@example.com");

        User existingUser =
            User.builder()
                .email("user@example.com")
                .passwordHash("existing-password-hash")
                .name("기존 사용자")
                .role(UserRole.USER)
                .locked(false)
                .build();

        when(
            userRepository.findByEmail(
                "user@example.com"
            )
        ).thenReturn(
            Optional.of(existingUser)
        );

        // when & then
        assertThatThrownBy(() ->
            seedAdminService.initializeAdmin()
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(
                "Seed 관리자 이메일과 동일한 일반 사용자 계정이 이미 존재합니다."
            );

        /*
         * 설정 충돌을 발견한 이후에는 비밀번호 해시 생성이나
         * 사용자 저장을 수행하면 안 됩니다.
         */
        verifyNoInteractions(passwordEncoder);

        verify(
            userRepository,
            never()
        ).saveAndFlush(
            org.mockito.ArgumentMatchers.any(User.class)
        );

        /*
         * 기존 일반 사용자의 권한과 계정 상태도 변경되지 않아야 합니다.
         */
        assertThat(existingUser.getRole())
            .isEqualTo(UserRole.USER);

        assertThat(existingUser.getPasswordHash())
            .isEqualTo("existing-password-hash");

        assertThat(existingUser.getName())
            .isEqualTo("기존 사용자");
    }

    @Test
    @DisplayName("기존 ADMIN 확인 시 관리자 비밀번호 원문을 조회하지 않는다")
    void initializeAdmin_doesNotReadPasswordWhenAdminAlreadyExists() {
        // given
        when(seedAdminProperties.getEmail())
            .thenReturn("admin@example.com");

        User existingAdmin =
            User.builder()
                .email("admin@example.com")
                .passwordHash("existing-password-hash")
                .name("기존 관리자")
                .role(UserRole.ADMIN)
                .locked(false)
                .build();

        when(
            userRepository.findByEmail(
                "admin@example.com"
            )
        ).thenReturn(
            Optional.of(existingAdmin)
        );

        // when
        boolean created =
            seedAdminService.initializeAdmin();

        // then
        assertThat(created).isFalse();

        /*
         * 멱등 실행에서는 불필요하게 민감한 비밀번호 설정값을
         * 읽거나 처리하지 않는지 확인합니다.
         */
        verify(
            seedAdminProperties,
            never()
        ).getPassword();

        verify(
            seedAdminProperties,
            never()
        ).getName();

        verifyNoInteractions(passwordEncoder);
    }
}
