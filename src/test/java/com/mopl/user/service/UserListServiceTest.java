package com.mopl.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.global.common.CursorResponse;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.util.CursorUtils;
import com.mopl.user.dto.UserDto;
import com.mopl.user.dto.UserListRequest;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.UserRepository;
import com.mopl.user.storage.ProfileImageStorage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 관리자 사용자 목록 조회 Service의 응답 조립과 커서 검증을 테스트
 */
@ExtendWith(MockitoExtension.class)
class UserListServiceTest {

    private static final Instant CREATED_AT =
        Instant.parse("2026-08-07T00:00:00Z");

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private ProfileImageStorage profileImageStorage;

    @InjectMocks
    private UserService userService;

    @ParameterizedTest
    @ValueSource(strings = {
        "name",
        "email",
        "createdAt",
        "locked",
        "role"
    })
    @DisplayName("정렬 기준에 맞는 다음 커서를 생성한다")
    void findUsers_success_buildNextCursor(
        String sortBy
    ) {
        // given
        User first = createUser(
            UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
            ),
            "first@example.com",
            "첫 번째 사용자",
            UserRole.USER,
            false
        );

        User additional = createUser(
            UUID.fromString(
                "22222222-2222-2222-2222-222222222222"
            ),
            "second@example.com",
            "추가 조회 사용자",
            UserRole.ADMIN,
            true
        );

        UserListRequest request = new UserListRequest(
            null,
            null,
            null,
            null,
            null,
            1,
            "ASCENDING",
            sortBy
        );

        /*
         * limit이 1인데 Repository가 2건을 반환하므로
         * 두 번째 사용자는 hasNext 판단에만 사용
         */
        when(userRepository.findUsers(request))
            .thenReturn(List.of(first, additional));

        when(userRepository.countUsers(request))
            .thenReturn(2L);

        // when
        CursorResponse<UserDto> response =
            userService.findUsers(request);

        // then
        assertThat(response.data()).hasSize(1);
        assertThat(response.data().get(0).id())
            .isEqualTo(first.getId());

        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextIdAfter())
            .isEqualTo(first.getId());

        assertThat(
            CursorUtils.decode(response.nextCursor())
        ).isEqualTo(expectedCursorValue(first, sortBy));

        assertThat(response.totalCount()).isEqualTo(2L);
        assertThat(response.sortBy()).isEqualTo(sortBy);
        assertThat(response.sortDirection())
            .isEqualTo("ASCENDING");

        verify(userRepository).findUsers(request);
        verify(userRepository).countUsers(request);
    }

    @Test
    @DisplayName("다음 사용자가 없으면 다음 커서를 반환하지 않는다")
    void findUsers_success_withoutNextPage() {
        // given
        User user = createUser(
            UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
            ),
            "user@example.com",
            "사용자",
            UserRole.USER,
            false
        );

        UserListRequest request = new UserListRequest(
            null,
            null,
            null,
            null,
            null,
            20,
            "DESCENDING",
            "createdAt"
        );

        when(userRepository.findUsers(request))
            .thenReturn(List.of(user));

        when(userRepository.countUsers(request))
            .thenReturn(1L);

        // when
        CursorResponse<UserDto> response =
            userService.findUsers(request);

        // then
        assertThat(response.data()).hasSize(1);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
        assertThat(response.nextIdAfter()).isNull();
        assertThat(response.totalCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("cursor와 idAfter 중 하나만 있으면 조회를 거부한다")
    void findUsers_fail_whenCursorPairIsIncomplete() {
        // given
        UserListRequest request = new UserListRequest(
            null,
            null,
            null,
            CursorUtils.encode("user@example.com"),
            null,
            20,
            "ASCENDING",
            "email"
        );

        // when & then
        assertThatThrownBy(
            () -> userService.findUsers(request)
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_INPUT);

        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("Base64 형식이 아닌 커서는 조회를 거부한다")
    void findUsers_fail_whenCursorIsMalformed() {
        // given
        UserListRequest request = new UserListRequest(
            null,
            null,
            null,
            "%%%잘못된커서%%%",
            UUID.randomUUID(),
            20,
            "ASCENDING",
            "email"
        );

        // when & then
        assertThatThrownBy(
            () -> userService.findUsers(request)
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_INPUT);

        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("true 또는 false가 아닌 잠금 상태 커서는 조회를 거부한다")
    void findUsers_fail_whenLockedCursorIsInvalid() {
        // given
        UserListRequest request = new UserListRequest(
            null,
            null,
            null,
            CursorUtils.encode("not-boolean"),
            UUID.randomUUID(),
            20,
            "ASCENDING",
            "locked"
        );

        // when & then
        assertThatThrownBy(
            () -> userService.findUsers(request)
        )
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_INPUT);

        verifyNoInteractions(userRepository);
    }

    private String expectedCursorValue(
        User user,
        String sortBy
    ) {
        return switch (sortBy) {
            case "name" -> user.getName();
            case "email" -> user.getEmail();
            case "createdAt" ->
                user.getCreatedAt().toString();
            case "locked" ->
                Boolean.toString(user.isLocked());
            case "role" -> user.getRole().name();
            default -> throw new IllegalArgumentException(
                "지원하지 않는 테스트 정렬 기준입니다."
            );
        };
    }

    private User createUser(
        UUID id,
        String email,
        String name,
        UserRole role,
        boolean locked
    ) {
        User user = User.builder()
            .email(email)
            .passwordHash("encoded-password")
            .name(name)
            .role(role)
            .locked(locked)
            .build();

        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(
            user,
            "createdAt",
            CREATED_AT
        );
        ReflectionTestUtils.setField(
            user,
            "updatedAt",
            CREATED_AT
        );

        return user;
    }
}
