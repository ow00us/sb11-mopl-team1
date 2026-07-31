package com.mopl.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.dto.UserCreateRequest;
import com.mopl.user.dto.UserDto;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.UserRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

// 회원가입 비즈니스 규칙 검증 단위 테스트
// Repository와 PasswordEncoder는 실제 구현 대신 Mock 사용
// 도커, PostgreSQL 없이 회원가입 로직만 검증 가능

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    UserRepository userRepository;

    @Mock
    PasswordEncoder passwordEncoder;

    @InjectMocks
    UserService userService;

    @Test
    @DisplayName("회원가입 시 이메일을 정규화하고 비밀번호 해시를 저장한다.")
    void signUp_success() {
        // given: 사용자는 대문자와 앞뒤 공백이 포함된 이메일을 입력할 수 있다.
        UserCreateRequest request = new UserCreateRequest(
            "테스트 사용자",
            " User@Example.CoM ",
            "passwordTest1!"
        );

        // 회원가입 서비스가 정규화한 뒤 사용할 이메일
        String normalizedEmail = "user@example.com";

        when(userRepository.existsByEmail(normalizedEmail)).thenReturn(false);
        when(passwordEncoder.encode("passwordTest1!")).thenReturn("encoded-password");

        // saveAndFlush()가 받은 User에 테스트용 ID와 생성 시각을 넣어 반환하도록 설정
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);

            ReflectionTestUtils.setField(
                user,
                "id",
                UUID.fromString("11111111-1111-1111-1111-111111111111")
            );
            ReflectionTestUtils.setField(
                user,
                "createdAt",
                Instant.parse("2026-07-28T03:00:00Z")
            );

            return user;
        });

        // when
        UserDto response = userService.signUp(request);

        // then: API 응답에는 정규화된 이메일과 안전한 사용자 정보만 포함
        assertThat(response.id())
            .isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        assertThat(response.email()).isEqualTo(normalizedEmail);
        assertThat(response.name()).isEqualTo("테스트 사용자");
        assertThat(response.role()).isEqualTo(UserRole.USER);
        assertThat(response.locked()).isFalse();

        // 실제로 저장하려 했던 User 엔티티 값 검증
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(userCaptor.capture());

        // 회원가입은 saveAndFlush() 한 번으로만 저장
        verify(userRepository, never()).save(any(User.class));

        User savedUser = userCaptor.getValue();

        assertThat(savedUser.getEmail()).isEqualTo(normalizedEmail);
        assertThat(savedUser.getPasswordHash()).isEqualTo("encoded-password");
        assertThat(savedUser.getName()).isEqualTo("테스트 사용자");
        assertThat(savedUser.getRole()).isEqualTo(UserRole.USER);
        assertThat(savedUser.isLocked()).isFalse();

        verify(userRepository).existsByEmail(normalizedEmail);
        verify(passwordEncoder).encode("passwordTest1!");
    }

    @Test
    @DisplayName("이미 가입된 이메일이면 회원가입을 막는다.")
    void signUp_fail_whenEmailAlreadyExists() {
        // given
        UserCreateRequest request = new UserCreateRequest(
            "테스트 사용자",
            "user@example.com",
            "passwordTest1!"
        );

        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);

        // when & then
        assertThatThrownBy(() -> userService.signUp(request))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.EMAIL_DUPLICATE);

        // 중복이면 비밀번호를 해시하거나 DB에 저장하면 안된다.
        verify(passwordEncoder, never()).encode(any());
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    @DisplayName("동시 가입으로 이메일 유니크 제약에 걸리면 중복 이메일 오류를 반환한다")
    void signUp_fail_whenDatabaseUniqueConstraintIsViolated() {
        // given
        UserCreateRequest request = new UserCreateRequest(
            "테스트 사용자",
            "user@example.com",
            "passwordTest1!"
        );

        when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
        when(passwordEncoder.encode("passwordTest1!")).thenReturn("encoded-password");
        when(userRepository.saveAndFlush(any(User.class)))
            .thenThrow(new DataIntegrityViolationException("uk_users_email"));

        // when & then
        assertThatThrownBy(() -> userService.signUp(request))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.EMAIL_DUPLICATE);
    }

    @Test
    @DisplayName("인증된 사용자 ID로 자신의 프로필을 조회한다")
    void getMyProfile_success() {
        // given: JWT 인증 정보에서 꺼냈다고 가정하는 사용자 UUID
        UUID userId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        Instant createdAt =
            Instant.parse("2026-07-28T03:00:00Z");

        // Repository가 반환할 사용자 엔티티를 준비합니다.
        User user = User.builder()
            .email("user@example.com")
            .passwordHash("encoded-password")
            .name("테스트 사용자")
            .profileImageUrl("https://example.com/profile.png")
            .role(UserRole.USER)
            .locked(false)
            .build();

        // id와 createdAt은 실제 환경에서는 JPA와 BaseEntity가 설정
        // 이 테스트에서는 DB를 사용하지 않으므로 테스트용 값을 직접 주입
        ReflectionTestUtils.setField(user, "id", userId);
        ReflectionTestUtils.setField(user, "createdAt", createdAt);

        when(userRepository.findById(userId))
            .thenReturn(Optional.of(user));

        // when: 인증된 사용자의 UUID로 자신의 프로필을 조회
        UserDto response = userService.getMyProfile(userId);

        // then: 비밀번호 해시를 제외한 사용자 정보가 UserDto로 반환
        assertThat(response.id()).isEqualTo(userId);
        assertThat(response.createdAt()).isEqualTo(createdAt);
        assertThat(response.email()).isEqualTo("user@example.com");
        assertThat(response.name()).isEqualTo("테스트 사용자");
        assertThat(response.profileImageUrl())
            .isEqualTo("https://example.com/profile.png");
        assertThat(response.role()).isEqualTo(UserRole.USER);
        assertThat(response.locked()).isFalse();

        // 전달받은 인증 사용자 ID로 한 번 조회했는지 확인
        verify(userRepository).findById(userId);
    }

    @Test
    @DisplayName("인증된 사용자 ID에 해당하는 계정이 없으면 조회에 실패한다")
    void getMyProfile_fail_whenUserDoesNotExist() {
        // given: JWT는 유효하지만 해당 사용자가 이미 탈퇴·삭제된 상황을 가정
        UUID userId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        when(userRepository.findById(userId))
            .thenReturn(Optional.empty());

        // when & then: 존재하지 않는 사용자이므로 404에 대응하는 예외가 발생
        assertThatThrownBy(() -> userService.getMyProfile(userId))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        verify(userRepository).findById(userId);
    }

}
