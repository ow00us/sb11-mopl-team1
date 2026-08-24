package com.mopl.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.UserRepository;
import com.mopl.user.storage.RefreshTokenStore;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * OAuth 로컬 로그인 수단 DB 등록 서비스 검증
 */
@ExtendWith(MockitoExtension.class)
class OAuthLocalCredentialRegistrationServiceTest {

    private static final UUID USER_ID =
        UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

    private static final String EMAIL =
        "user@example.com";

    private static final String ENCODED_PASSWORD =
        "encoded-password";

    @Mock
    UserRepository userRepository;

    @Mock
    RefreshTokenStore refreshTokenStore;

    OAuthLocalCredentialRegistrationService service;

    @BeforeEach
    void setUp() {
        service =
            new OAuthLocalCredentialRegistrationService(
                userRepository,
                refreshTokenStore
            );
    }

    @Test
    @DisplayName("OAuth 전용 사용자에게 이메일과 비밀번호 해시를 등록한다")
    void register_success() {
        // given
        User user =
            oauthOnlyUser(false);

        when(
            userRepository.findByIdForUpdate(
                USER_ID
            )
        ).thenReturn(
            Optional.of(user)
        );

        when(
            userRepository.existsByEmail(
                EMAIL
            )
        ).thenReturn(false);

        // when
        service.register(
            USER_ID,
            EMAIL,
            ENCODED_PASSWORD
        );

        // then
        assertThat(user.getEmail())
            .isEqualTo(EMAIL);

        assertThat(user.getPasswordHash())
            .isEqualTo(
                ENCODED_PASSWORD
            );

        verify(userRepository).flush();

        verify(refreshTokenStore)
            .revokeAllByUserId(
                USER_ID
            );
    }

    @Test
    @DisplayName("존재하지 않는 사용자는 404 오류를 반환한다")
    void register_rejectsMissingUser() {
        // given
        when(
            userRepository.findByIdForUpdate(
                USER_ID
            )
        ).thenReturn(
            Optional.empty()
        );

        // when
        BusinessException exception =
            catchThrowableOfType(
                () ->
                    service.register(
                        USER_ID,
                        EMAIL,
                        ENCODED_PASSWORD
                    ),
                BusinessException.class
            );

        // then
        assertThat(exception.getErrorCode())
            .isEqualTo(
                ErrorCode.RESOURCE_NOT_FOUND
            );

        verify(
            refreshTokenStore,
            never()
        ).revokeAllByUserId(
            USER_ID
        );
    }

    @Test
    @DisplayName("잠긴 사용자는 로컬 로그인 수단을 등록할 수 없다")
    void register_rejectsLockedUser() {
        // given
        when(
            userRepository.findByIdForUpdate(
                USER_ID
            )
        ).thenReturn(
            Optional.of(
                oauthOnlyUser(true)
            )
        );

        // when
        BusinessException exception =
            catchThrowableOfType(
                () ->
                    service.register(
                        USER_ID,
                        EMAIL,
                        ENCODED_PASSWORD
                    ),
                BusinessException.class
            );

        // then
        assertThat(exception.getErrorCode())
            .isEqualTo(
                ErrorCode.FORBIDDEN
            );

        verify(
            refreshTokenStore,
            never()
        ).revokeAllByUserId(
            USER_ID
        );
    }

    @Test
    @DisplayName("이미 로컬 비밀번호가 있으면 다시 등록할 수 없다")
    void register_rejectsExistingCredential() {
        // given
        when(
            userRepository.findByIdForUpdate(
                USER_ID
            )
        ).thenReturn(
            Optional.of(
                localUser()
            )
        );

        // when
        BusinessException exception =
            catchThrowableOfType(
                () ->
                    service.register(
                        USER_ID,
                        EMAIL,
                        ENCODED_PASSWORD
                    ),
                BusinessException.class
            );

        // then
        assertThat(exception.getErrorCode())
            .isEqualTo(
                ErrorCode.LOCAL_CREDENTIAL_ALREADY_EXISTS
            );

        verify(
            refreshTokenStore,
            never()
        ).revokeAllByUserId(
            USER_ID
        );
    }

    @Test
    @DisplayName("이미 사용 중인 이메일은 등록할 수 없다")
    void register_rejectsDuplicatedEmail() {
        // given
        when(
            userRepository.findByIdForUpdate(
                USER_ID
            )
        ).thenReturn(
            Optional.of(
                oauthOnlyUser(false)
            )
        );

        when(
            userRepository.existsByEmail(
                EMAIL
            )
        ).thenReturn(true);

        // when
        BusinessException exception =
            catchThrowableOfType(
                () ->
                    service.register(
                        USER_ID,
                        EMAIL,
                        ENCODED_PASSWORD
                    ),
                BusinessException.class
            );

        // then
        assertThat(exception.getErrorCode())
            .isEqualTo(
                ErrorCode.EMAIL_DUPLICATE
            );

        verify(
            userRepository,
            never()
        ).flush();

        verify(
            refreshTokenStore,
            never()
        ).revokeAllByUserId(
            USER_ID
        );
    }

    @Test
    @DisplayName("DB 고유 제약 충돌도 이메일 중복 오류로 변환한다")
    void register_mapsUniqueConstraintConflict() {
        // given
        User user =
            oauthOnlyUser(false);

        when(
            userRepository.findByIdForUpdate(
                USER_ID
            )
        ).thenReturn(
            Optional.of(user)
        );

        when(
            userRepository.existsByEmail(
                EMAIL
            )
        ).thenReturn(false);

        org.mockito.Mockito.doThrow(
                new DataIntegrityViolationException(
                    "users email unique constraint"
                )
            )
            .when(userRepository)
            .flush();

        // when
        BusinessException exception =
            catchThrowableOfType(
                () ->
                    service.register(
                        USER_ID,
                        EMAIL,
                        ENCODED_PASSWORD
                    ),
                BusinessException.class
            );

        // then
        assertThat(exception.getErrorCode())
            .isEqualTo(
                ErrorCode.EMAIL_DUPLICATE
            );

        verify(
            refreshTokenStore,
            never()
        ).revokeAllByUserId(
            USER_ID
        );
    }

    @Test
    @DisplayName("Refresh Token 세션 폐기 실패를 숨기지 않는다")
    void register_propagatesRefreshTokenRevocationFailure() {
        // given
        User user =
            oauthOnlyUser(false);

        when(
            userRepository.findByIdForUpdate(
                USER_ID
            )
        ).thenReturn(
            Optional.of(user)
        );

        when(
            userRepository.existsByEmail(
                EMAIL
            )
        ).thenReturn(false);

        IllegalStateException redisException =
            new IllegalStateException(
                "Redis 연결 실패"
            );

        org.mockito.Mockito.doThrow(
                redisException
            )
            .when(refreshTokenStore)
            .revokeAllByUserId(
                USER_ID
            );

        // when & then
        assertThatThrownBy(
            () ->
                service.register(
                    USER_ID,
                    EMAIL,
                    ENCODED_PASSWORD
                )
        ).isSameAs(redisException);
    }

    private User oauthOnlyUser(
        boolean locked
    ) {
        return User.builder()
            .email(
                "google-user@oauth.invalid"
            )
            .passwordHash(null)
            .name("OAuth 사용자")
            .profileImageUrl(null)
            .role(UserRole.USER)
            .locked(locked)
            .build();
    }

    private User localUser() {
        return User.builder()
            .email(
                "local@example.com"
            )
            .passwordHash(
                "existing-password-hash"
            )
            .name("로컬 사용자")
            .profileImageUrl(null)
            .role(UserRole.USER)
            .locked(false)
            .build();
    }
}
