package com.mopl.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.dto.UserCreateRequest;
import com.mopl.user.dto.UserDto;
import com.mopl.user.dto.UserUpdateRequest;
import com.mopl.user.dto.UserLockUpdateRequest;
import com.mopl.user.dto.UserRoleUpdateRequest;
import com.mopl.user.dto.ChangePasswordRequest;
import com.mopl.user.storage.ProfileImageStorage;
import com.mopl.user.storage.RefreshTokenStore;
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
import org.springframework.mock.web.MockMultipartFile;

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

    /**
     * 실제 파일 저장소를 사용하지 않고 프로필 이미지 업로드 결과를 제어
     */
    @Mock
    ProfileImageStorage profileImageStorage;

    @Mock
    RefreshTokenStore refreshTokenStore;

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
    // @DisplayName("인증된 사용자 ID로 자신의 프로필을 조회한다")
    @DisplayName("사용자 ID로 사용자 상세 정보를 조회한다")
        // void getMyProfile_success() { /api/users/me 변환
    void findUser_success() {
        // // given: JWT 인증 정보에서 꺼냈다고 가정하는 사용자 UUID (/users/me)
        // given: 조회할 사용자의 UUID
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
        // UserDto response = userService.getMyProfile(userId);
        UserDto response = userService.findUser(userId);

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
    // @DisplayName("인증된 사용자 ID에 해당하는 계정이 없으면 조회에 실패한다")
    @DisplayName("사용자 ID에 해당하는 계정이 없으면 조회에 실패한다")
        // void getMyProfile_fail_whenUserDoesNotExist() { /users/me
    void findUser_fail_whenUserDoesNotExist() {
        // given: JWT는 유효하지만 해당 사용자가 이미 탈퇴·삭제된 상황을 가정
        UUID userId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        when(userRepository.findById(userId))
            .thenReturn(Optional.empty());

        // when & then: 존재하지 않는 사용자이므로 404에 대응하는 예외가 발생
        // assertThatThrownBy(() -> userService.getMyProfile(userId))
        assertThatThrownBy(() -> userService.findUser(userId))

            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        verify(userRepository).findById(userId);
    }

    @Test
    @DisplayName("본인은 이름과 프로필 이미지를 함께 수정할 수 있다")
    void updateUser_success_whenNameAndImageAreProvided() {
        // given
        UUID userId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        User user = createUserFixture(userId);

        UserUpdateRequest request =
            new UserUpdateRequest("변경된 사용자");

        /*
         * 실제 이미지 파일을 생성하지 않고 multipart 요청에서 전달될
         * MultipartFile과 같은 역할을 하는 테스트 파일을 준비
         */
        MockMultipartFile image = new MockMultipartFile(
            "image",
            "profile.png",
            "image/png",
            new byte[]{1, 2, 3}
        );

        when(userRepository.findById(userId))
            .thenReturn(Optional.of(user));

        when(profileImageStorage.upload(image))
            .thenReturn(
                "https://placeholder.mopl.local/profile-images/new-profile.png"
            );

        // when
        UserDto response = userService.updateUser(
            userId,
            userId,
            request,
            image
        );

        // then
        assertThat(response.name())
            .isEqualTo("변경된 사용자");

        assertThat(response.profileImageUrl())
            .isEqualTo(
                "https://placeholder.mopl.local/profile-images/new-profile.png"
            );

        /*
         * 반환된 DTO뿐만 아니라 실제 엔티티 상태도 변경되었는지 확인
         * JPA 변경 감지는 이 엔티티의 변경 상태를 기준으로 동작
         */
        assertThat(user.getName())
            .isEqualTo("변경된 사용자");

        assertThat(user.getProfileImageUrl())
            .isEqualTo(
                "https://placeholder.mopl.local/profile-images/new-profile.png"
            );

        verify(userRepository).findById(userId);
        verify(profileImageStorage).upload(image);

        /*
         * 조회한 영속 엔티티는 트랜잭션 종료 시 변경 감지가 동작하므로
         * save()를 명시적으로 호출하지 않는지 확인
         */
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("이미지가 없으면 이름만 변경하고 기존 프로필 이미지를 유지한다")
    void updateUser_success_whenOnlyNameIsProvided() {
        // given
        UUID userId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        User user = createUserFixture(userId);

        UserUpdateRequest request =
            new UserUpdateRequest("변경된 사용자");

        when(userRepository.findById(userId))
            .thenReturn(Optional.of(user));

        // when
        UserDto response = userService.updateUser(
            userId,
            userId,
            request,
            null
        );

        // then
        assertThat(response.name())
            .isEqualTo("변경된 사용자");

        assertThat(response.profileImageUrl())
            .isEqualTo("https://example.com/old-profile.png");

        assertThat(user.getName())
            .isEqualTo("변경된 사용자");

        assertThat(user.getProfileImageUrl())
            .isEqualTo("https://example.com/old-profile.png");

        /*
         * 이미지가 전달되지 않았으므로 저장소 업로드는 실행되면 안 된다.
         */
        verifyNoInteractions(profileImageStorage);
        verify(userRepository).findById(userId);
    }

    @Test
    @DisplayName("인증 정보가 없으면 프로필 수정에 실패한다")
    void updateUser_fail_whenAuthenticationDoesNotExist() {
        // given
        UUID userId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        UserUpdateRequest request =
            new UserUpdateRequest("변경된 사용자");

        // when & then
        assertThatThrownBy(() ->
            userService.updateUser(
                null,
                userId,
                request,
                null
            )
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.UNAUTHORIZED);

        /*
         * 인증되지 않은 요청은 사용자 조회나 이미지 업로드를
         * 수행하기 전에 중단되어야 한다.
         */
        verifyNoInteractions(
            userRepository,
            profileImageStorage
        );
    }

    @Test
    @DisplayName("다른 사용자의 프로필을 수정하려 하면 실패한다")
    void updateUser_fail_whenUpdatingAnotherUser() {
        // given
        UUID authenticatedUserId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        UUID targetUserId =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

        UserUpdateRequest request =
            new UserUpdateRequest("변경된 사용자");

        // when & then
        assertThatThrownBy(() ->
            userService.updateUser(
                authenticatedUserId,
                targetUserId,
                request,
                null
            )
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.FORBIDDEN);

        /*
         * 권한 검사를 사용자 조회보다 먼저 수행하므로
         * 다른 사용자의 존재 여부를 데이터베이스에서 조회하지 않는다.
         */
        verifyNoInteractions(
            userRepository,
            profileImageStorage
        );
    }

    @Test
    @DisplayName("수정할 사용자가 존재하지 않으면 프로필 수정에 실패한다")
    void updateUser_fail_whenUserDoesNotExist() {
        // given
        UUID userId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        UserUpdateRequest request =
            new UserUpdateRequest("변경된 사용자");

        when(userRepository.findById(userId))
            .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
            userService.updateUser(
                userId,
                userId,
                request,
                null
            )
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        verify(userRepository).findById(userId);

        /*
         * 사용자가 없으면 이미지를 업로드해서는 안 된다.
         * DB에 반영할 사용자도 없는데 파일부터 저장하면
         * 사용되지 않는 이미지가 저장소에 남을 수 있다.
         */
        verifyNoInteractions(profileImageStorage);
    }

    @Test
    @DisplayName("본인은 새 비밀번호를 인코딩하여 변경할 수 있다")
    void changePassword_success() {
        // given
        UUID userId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        User user = createUserFixture(userId);

        ChangePasswordRequest request =
            new ChangePasswordRequest("newPassword1!");

        String encodedPassword =
            "$2a$10$new-encoded-password";

        when(userRepository.findById(userId))
            .thenReturn(Optional.of(user));

        when(passwordEncoder.encode("newPassword1!"))
            .thenReturn(encodedPassword);

        // when
        userService.changePassword(
            userId,
            userId,
            request
        );

        // then
        /*
         * User 엔티티에는 요청으로 전달된 비밀번호 원문이 아니라
         * PasswordEncoder가 반환한 해시만 저장되어야 함.
         */
        assertThat(user.getPasswordHash())
            .isEqualTo(encodedPassword);

        assertThat(user.getPasswordHash())
            .isNotEqualTo("newPassword1!");

        verify(userRepository).findById(userId);
        verify(passwordEncoder).encode("newPassword1!");
        verify(refreshTokenStore).revokeAllByUserId(userId);

        /*
         * 조회한 영속 엔티티는 트랜잭션 종료 시 JPA 변경 감지로
         * UPDATE되므로 save()를 명시적으로 호출하지 않음.
         */
        verify(userRepository, never())
            .save(any(User.class));
    }

    @Test
    @DisplayName("Refresh Token 전체 세션 폐기에 실패하면 비밀번호 변경 요청도 실패한다")
    void changePassword_fail_whenRefreshTokenRevocationFails() {
        // given
        UUID userId =
            UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
            );

        User user =
            createUserFixture(userId);

        ChangePasswordRequest request =
            new ChangePasswordRequest(
                "newPassword1!"
            );

        String encodedPassword =
            "$2a$10$new-encoded-password";

        when(userRepository.findById(userId))
            .thenReturn(
                Optional.of(user)
            );

        when(
            passwordEncoder.encode(
                "newPassword1!"
            )
        ).thenReturn(encodedPassword);

        /*
         * Redis 장애 또는 명령 실행 실패 상황을 재현
         * 실제 구현체도 비정상적인 Redis 결과를 정상 결과로
         * 숨기지 않고 런타임 예외를 전달
         */
        when(
            refreshTokenStore
                .revokeAllByUserId(userId)
        ).thenThrow(
            new IllegalStateException(
                "Redis 세션 폐기 실패"
            )
        );

        // when & then
        assertThatThrownBy(() ->
            userService.changePassword(
                userId,
                userId,
                request
            )
        )
            .isInstanceOf(
                IllegalStateException.class
            )
            .hasMessage(
                "Redis 세션 폐기 실패"
            );

        verify(userRepository)
            .findById(userId);

        verify(passwordEncoder)
            .encode("newPassword1!");

        verify(refreshTokenStore)
            .revokeAllByUserId(userId);

        /*
         * 영속 엔티티는 변경 감지로 저장하므로
         * 명시적인 save() 호출은 없어야 한다.
         *
         * 실제 데이터베이스 롤백 여부는 단위 테스트가 아닌
         * 트랜잭션 통합 테스트에서 별도로 검증
         */
        verify(userRepository, never())
            .save(any(User.class));
    }

    @Test
    @DisplayName("인증 정보가 없으면 비밀번호 변경에 실패한다")
    void changePassword_fail_whenAuthenticationDoesNotExist() {
        // given
        UUID userId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        ChangePasswordRequest request =
            new ChangePasswordRequest("newPassword1!");

        // when & then
        assertThatThrownBy(() ->
            userService.changePassword(
                null,
                userId,
                request
            )
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.UNAUTHORIZED);

        /*
         * 인증되지 않은 요청은 DB 조회와 BCrypt 인코딩을
         * 수행하기 전에 중단되어야 한다.
         */
        verifyNoInteractions(
            userRepository,
            passwordEncoder
        );
    }

    @Test
    @DisplayName("다른 사용자의 비밀번호를 변경하려 하면 실패한다")
    void changePassword_fail_whenChangingAnotherUserPassword() {
        // given
        UUID authenticatedUserId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        UUID targetUserId =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

        ChangePasswordRequest request =
            new ChangePasswordRequest("newPassword1!");

        // when & then
        assertThatThrownBy(() ->
            userService.changePassword(
                authenticatedUserId,
                targetUserId,
                request
            )
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.FORBIDDEN);

        /*
         * 본인이 아닌 경우 사용자 존재 여부를 확인하지 않고
         * 비밀번호 인코딩도 수행하지 않음.
         */
        verifyNoInteractions(
            userRepository,
            passwordEncoder
        );
    }

    @Test
    @DisplayName("변경할 사용자가 존재하지 않으면 비밀번호 변경에 실패한다")
    void changePassword_fail_whenUserDoesNotExist() {
        // given
        UUID userId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        ChangePasswordRequest request =
            new ChangePasswordRequest("newPassword1!");

        when(userRepository.findById(userId))
            .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
            userService.changePassword(
                userId,
                userId,
                request
            )
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        verify(userRepository).findById(userId);

        /*
         * 사용자가 존재하지 않으면 비용이 큰 BCrypt 인코딩을
         * 수행하면 안된다.
         */
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    @DisplayName("관리자는 일반 사용자를 관리자로 변경할 수 있다")
    void updateRole_success_whenChangingUserToAdmin() {
        // given
        UUID userId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        /*
         * createUserFixture()는 기본 권한이 USER인 사용자를 생성
         */
        User user = createUserFixture(userId);

        UserRoleUpdateRequest request =
            new UserRoleUpdateRequest(UserRole.ADMIN);

        when(userRepository.findById(userId))
            .thenReturn(Optional.of(user));

        // when
        userService.updateRole(
            userId,
            request
        );

        // then
        assertThat(user.getRole())
            .isEqualTo(UserRole.ADMIN);

        /*
         * 변경 대상 사용자는 한 번만 조회해야 한다.
         */
        verify(userRepository).findById(userId);

        /*
         * 조회한 User는 영속 엔티티이므로 JPA 변경 감지를 사용
         * 따라서 save()를 명시적으로 호출하지 않는다.
         */
        verify(userRepository, never())
            .save(any(User.class));

        /*
         * 변경 전 권한으로 생성된 Refresh Token 세션을
         * 모두 폐기해야 한다.
         */
        verify(refreshTokenStore)
            .revokeAllByUserId(userId);
    }

    @Test
    @DisplayName("관리자는 관리자를 일반 사용자로 변경할 수 있다")
    void updateRole_success_whenChangingAdminToUser() {
        // given
        UUID userId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        User user = createUserFixture(userId);

        /*
         * createUserFixture()는 기본 권한이 USER
         *
         * 테스트 대상인 updateRole()을 준비 과정에서 호출하면
         * 검증할 메서드가 이미 정상 동작한다고 가정
         * 따라서 ReflectionTestUtils로 초기 권한만 ADMIN으로 설정
         */
        ReflectionTestUtils.setField(
            user,
            "role",
            UserRole.ADMIN
        );

        UserRoleUpdateRequest request =
            new UserRoleUpdateRequest(UserRole.USER);

        when(userRepository.findById(userId))
            .thenReturn(Optional.of(user));

        // when
        userService.updateRole(
            userId,
            request
        );

        // then
        assertThat(user.getRole())
            .isEqualTo(UserRole.USER);

        verify(userRepository).findById(userId);

        verify(userRepository, never())
            .save(any(User.class));

        /*
         * 변경 전 권한으로 생성된 Refresh Token 세션을 모두 폐기
         */
        verify(refreshTokenStore)
            .revokeAllByUserId(userId);
    }

    @Test
    @DisplayName("현재 권한과 요청 권한이 같으면 세션을 폐기하지 않는다")
    void updateRole_doesNotRevokeSessionsWhenRoleDoesNotChange() {
        // given
        UUID userId =
            UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
            );

        /*
         * createUserFixture()는 USER 권한을 가진 사용자를 생성
         */
        User user =
            createUserFixture(userId);

        UserRoleUpdateRequest request =
            new UserRoleUpdateRequest(
                UserRole.USER
            );

        when(userRepository.findById(userId))
            .thenReturn(
                Optional.of(user)
            );

        // when
        userService.updateRole(
            userId,
            request
        );

        // then
        assertThat(user.getRole())
            .isEqualTo(UserRole.USER);

        verify(userRepository)
            .findById(userId);

        /*
         * 실제 권한 변화가 없으므로 사용자를 모든 기기에서
         * 불필요하게 로그아웃시키지 않는다.
         */
        verifyNoInteractions(
            refreshTokenStore
        );

        verify(userRepository, never())
            .save(any(User.class));
    }

    @Test
    @DisplayName("권한을 변경할 사용자가 없으면 실패한다")
    void updateRole_fail_whenUserDoesNotExist() {
        // given
        UUID userId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        UserRoleUpdateRequest request =
            new UserRoleUpdateRequest(UserRole.ADMIN);

        when(userRepository.findById(userId))
            .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
            userService.updateRole(
                userId,
                request
            )
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        verify(userRepository).findById(userId);

        /*
         * 대상 사용자가 존재하지 않으므로
         * 저장과 관련된 추가 Repository 작업이 발생하면 안된다.
         */
        verify(userRepository, never())
            .save(any(User.class));

        verifyNoInteractions(
            refreshTokenStore
        );
    }

    @Test
    @DisplayName("관리자는 사용자 계정을 잠글 수 있다")
    void updateLocked_success_whenLockingUser() {
        // given
        UUID userId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        /*
         * createUserFixture()는 locked가 false인 사용자를 생성
         * 따라서 잠기지 않은 계정을 잠그는 상황
         */
        User user = createUserFixture(userId);

        UserLockUpdateRequest request =
            new UserLockUpdateRequest(true);

        when(userRepository.findById(userId))
            .thenReturn(Optional.of(user));

        // when
        userService.updateLocked(
            userId,
            request
        );

        // then
        assertThat(user.isLocked()).isTrue();

        /*
         * 대상 사용자는 한 번만 조회해야 한다.
         */
        verify(userRepository).findById(userId);

        /*
         * 조회한 User는 영속 엔티티이므로 JPA 변경 감지를 사용
         * 따라서 save()를 명시적으로 호출하지 않아야 한다.
         */
        verify(userRepository, never())
            .save(any(User.class));

        /*
         * 계정이 잠기면 해당 사용자의 모든 Refresh Token
         * Family를 폐기
         */
        verify(refreshTokenStore)
            .revokeAllByUserId(userId);
    }

    @Test
    @DisplayName("관리자는 사용자 계정 잠금을 해제할 수 있다")
    void updateLocked_success_whenUnlockingUser() {
        // given
        UUID userId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        User user = createUserFixture(userId);

        /*
         * createUserFixture()는 기본적으로 잠기지 않은 사용자를 만든다.
         *
         * 테스트 대상인 updateLocked()를 준비 과정에서 먼저 호출하면
         * 같은 메서드가 정상 동작한다고 가정하는 테스트가 되므로,
         * 테스트 준비 단계에서는 ReflectionTestUtils로 잠금 상태를 설정
         */
        ReflectionTestUtils.setField(
            user,
            "locked",
            true
        );

        UserLockUpdateRequest request =
            new UserLockUpdateRequest(false);

        when(userRepository.findById(userId))
            .thenReturn(Optional.of(user));

        // when
        userService.updateLocked(
            userId,
            request
        );

        // then
        assertThat(user.isLocked()).isFalse();

        verify(userRepository).findById(userId);

        verify(userRepository, never())
            .save(any(User.class));

        /*
         * 잠금 시점에 기존 세션이 이미 폐기됐으며,
         * 잠금 해제는 세션을 새로 생성하거나 복구하지 않는다.
         */
        verifyNoInteractions(
            refreshTokenStore
        );
    }

    @Test
    @DisplayName("잠금 상태를 변경할 사용자가 없으면 실패한다")
    void updateLocked_fail_whenUserDoesNotExist() {
        // given
        UUID userId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        UserLockUpdateRequest request =
            new UserLockUpdateRequest(true);

        when(userRepository.findById(userId))
            .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
            userService.updateLocked(
                userId,
                request
            )
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        verify(userRepository).findById(userId);

        /*
         * 사용자가 존재하지 않으므로 저장 또는 상태 변경과 관련된
         * 추가 Repository 작업이 발생하면 안된다.
         */
        verify(userRepository, never())
            .save(any(User.class));

        verifyNoInteractions(
            refreshTokenStore
        );
    }

    /**
     * 프로필 수정 테스트에서 공통으로 사용하는 사용자 엔티티를 생성
     *
     * 실제 애플리케이션에서는 id와 createdAt을 JPA가 설정하지만,
     * 단위 테스트에서는 데이터베이스를 사용하지 않으므로 직접 주입
     *
     * @param userId 테스트 사용자 UUID
     * @return 기존 프로필 정보를 가진 테스트 사용자
     */
    private User createUserFixture(UUID userId) {
        User user = User.builder()
            .email("user@example.com")
            .passwordHash("encoded-password")
            .name("기존 사용자")
            .profileImageUrl("https://example.com/old-profile.png")
            .role(UserRole.USER)
            .locked(false)
            .build();

        ReflectionTestUtils.setField(
            user,
            "id",
            userId
        );

        ReflectionTestUtils.setField(
            user,
            "createdAt",
            Instant.parse("2026-08-01T03:00:00Z")
        );

        return user;
    }

    @Test
    @DisplayName("이미지가 아닌 파일이면 프로필 수정에 실패한다")
    void updateUser_fail_whenFileIsNotImage() {
        // given
        UUID userId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        User user = createUserFixture(userId);

        UserUpdateRequest request =
            new UserUpdateRequest("변경된 사용자");

        MockMultipartFile textFile = new MockMultipartFile(
            "image",
            "profile.txt",
            "text/plain",
            "not-image".getBytes()
        );

        when(userRepository.findById(userId))
            .thenReturn(Optional.of(user));

        // when & then
        assertThatThrownBy(() ->
            userService.updateUser(
                userId,
                userId,
                request,
                textFile
            )
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_INPUT);

        /*
         * 이미지가 아닌 파일은 실제 저장소로 전달되면 안 됩니다.
         */
        verifyNoInteractions(profileImageStorage);

        /*
         * 파일 검증에 실패했으므로 사용자 정보도 변경되면 안 됩니다.
         */
        assertThat(user.getName())
            .isEqualTo("기존 사용자");

        assertThat(user.getProfileImageUrl())
            .isEqualTo("https://example.com/old-profile.png");
    }

    @Test
    @DisplayName("파일의 Content-Type이 없으면 프로필 수정에 실패한다")
    void updateUser_fail_whenImageContentTypeDoesNotExist() {
        // given
        UUID userId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        User user = createUserFixture(userId);

        UserUpdateRequest request =
            new UserUpdateRequest("변경된 사용자");

        MockMultipartFile fileWithoutContentType =
            new MockMultipartFile(
                "image",
                "profile.png",
                null,
                new byte[]{1, 2, 3}
            );

        when(userRepository.findById(userId))
            .thenReturn(Optional.of(user));

        // when & then
        assertThatThrownBy(() ->
            userService.updateUser(
                userId,
                userId,
                request,
                fileWithoutContentType
            )
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_INPUT);

        verifyNoInteractions(profileImageStorage);

        assertThat(user.getName())
            .isEqualTo("기존 사용자");

        assertThat(user.getProfileImageUrl())
            .isEqualTo("https://example.com/old-profile.png");
    }

    @Test
    @DisplayName("빈 이미지 파일이면 기존 프로필 이미지를 유지한다")
    void updateUser_success_whenImageIsEmpty() {
        // given
        UUID userId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        User user = createUserFixture(userId);

        /*
         * 이름도 변경하지 않고 빈 이미지 파일만 전달되는 상황을 구성
         *
         * 현재 계약에서 빈 파일은 이미지 삭제 요청이 아니며,
         * 기존 프로필 이미지를 유지한다는 의미로 처리
         */
        UserUpdateRequest request =
            new UserUpdateRequest(null);

        MockMultipartFile emptyImage = new MockMultipartFile(
            "image",
            "profile.png",
            "image/png",
            new byte[0]
        );

        when(userRepository.findById(userId))
            .thenReturn(Optional.of(user));

        // when
        UserDto response = userService.updateUser(
            userId,
            userId,
            request,
            emptyImage
        );

        // then
        assertThat(response.name())
            .isEqualTo("기존 사용자");

        assertThat(response.profileImageUrl())
            .isEqualTo("https://example.com/old-profile.png");

        /*
         * 빈 파일은 새 이미지로 업로드하지 않는다.
         */
        verifyNoInteractions(profileImageStorage);

        /*
         * 사용자 엔티티의 기존 프로필 정보도 그대로 유지되어야 한다.
         */
        assertThat(user.getName())
            .isEqualTo("기존 사용자");

        assertThat(user.getProfileImageUrl())
            .isEqualTo("https://example.com/old-profile.png");

        verify(userRepository).findById(userId);
    }

}
