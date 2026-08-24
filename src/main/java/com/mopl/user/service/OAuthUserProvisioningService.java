package com.mopl.user.service;

import com.mopl.user.entity.OAuthAccount;
import com.mopl.user.entity.OAuthProvider;
import com.mopl.user.entity.User;
import com.mopl.user.repository.OAuthAccountRepository;
import com.mopl.user.repository.UserRepository;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/**
 * 외부 OAuth 계정을 모두의 플리 사용자와 연결하거나 신규 사용자를 생성
 *
 * <p>Provider 사용자 정보 조회는 외부 네트워크 I/O이므로 이 서비스 밖에서
 * 완료합니다. 이 서비스는 조회된 정보를 전달받아 짧은 DB 트랜잭션 안에서
 * User와 OAuthAccount만 처리합니다.</p>
 */
@Service
@RequiredArgsConstructor
public class OAuthUserProvisioningService {

    public static final String ACCOUNT_LINK_REQUIRED =
        "oauth_account_link_required";

    public static final String ACCOUNT_CREATION_CONFLICT =
        "oauth_account_creation_conflict";

    private static final int MAX_EMAIL_LENGTH = 255;
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_PROFILE_IMAGE_URL_LENGTH = 2048;

    private final OAuthAccountRepository oauthAccountRepository;
    private final UserRepository userRepository;
    private final OAuthUserCreationService oauthUserCreationService;

    /**
     * Provider 계정에 연결된 사용자를 조회하거나 소셜 전용 사용자를 생성
     *
     * <p>이미 연결된 Provider 계정은 외부 이메일이 변경되었더라도 기존
     * 모두의 플리 사용자에게 로그인시킵니다. 계정 식별 기준은 이메일이 아니라
     * Provider와 Provider 사용자 ID 조합이기 때문입니다.</p>
     *
     * <p>신규 Provider 계정의 이메일이 기존 사용자와 겹치면 이메일만으로
     * 자동 연결하지 않습니다. 두 인증수단의 소유권을 모두 확인하는 명시적인
     * 계정 연결 절차가 필요하므로 인증을 실패시킵니다.</p>
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public User resolveOrCreate(
        OAuthProvider provider,
        String providerUserId,
        String email,
        String name,
        String profileImageUrl
    ) {
        validateProviderIdentity(
            provider,
            providerUserId
        );

        /*
         * 이미 연결된 계정은 외부 프로필의 이메일·이름 유무와 관계없이
         * 저장된 MOPL 사용자를 반환
         */
        return oauthAccountRepository
            .findByProviderAndProviderUserId(
                provider,
                providerUserId
            )
            .map(OAuthAccount::getUser)
            .orElseGet(() ->
                createOAuthUser(
                    provider,
                    providerUserId,
                    email,
                    name,
                    profileImageUrl
                )
            );
    }

    /**
     * 연결 정보가 없는 Provider 사용자의 MOPL 계정과 연결 정보를 생성
     *
     * <p>동시에 같은 Provider 계정으로 최초 로그인을 시도하여
     * 유일성 제약 충돌이 발생하면, 별도 생성 트랜잭션이 롤백된 뒤
     * 먼저 생성된 OAuth 연결 정보를 다시 조회합니다.</p>
     */
    private User createOAuthUser(
        OAuthProvider provider,
        String providerUserId,
        String email,
        String name,
        String profileImageUrl
    ) {
        String normalizedEmail =
            normalizeAndValidateEmail(email);

        String validatedName =
            validateName(name);

        String validatedProfileImageUrl =
            validateProfileImageUrl(profileImageUrl);

        if (userRepository.existsByEmail(normalizedEmail)) {
            /*
             * 최초 조회 이후 동일 Provider 계정의 다른 로그인 요청이
             * User와 OAuthAccount 생성을 먼저 완료했을 수 있다.
             *
             * 따라서 이메일이 존재한다는 이유만으로 기존 로컬 계정이라고
             * 단정하지 않고, 동일 Provider 연결 정보를 다시 확인한다.
             */
            return oauthAccountRepository
                .findByProviderAndProviderUserId(
                    provider,
                    providerUserId
                )
                .map(OAuthAccount::getUser)
                .orElseThrow(() ->
                    authenticationException(
                        ACCOUNT_LINK_REQUIRED,
                        "동일 이메일의 기존 사용자에게 명시적인 계정 연결이 필요합니다.",
                        null
                    )
                );
        }

        try {
            /*
             * 실제 User와 OAuthAccount INSERT는 별도의 REQUIRES_NEW
             * 트랜잭션에서 실행
             */
            return oauthUserCreationService.create(
                provider,
                providerUserId,
                normalizedEmail,
                validatedName,
                validatedProfileImageUrl
            );
        } catch (DataIntegrityViolationException exception) {
            /*
             * 생성 트랜잭션이 완전히 롤백된 뒤 실행되는 경로
             * 같은 Provider 계정의 동시 요청이 먼저 성공했다면
             * 해당 연결 정보를 조회해 동일한 사용자로 수렴
             */
            return resolveAfterCreationConflict(
                provider,
                providerUserId,
                normalizedEmail,
                exception
            );
        }
    }

    /**
     * OAuth 사용자 생성 충돌 이후 최종 저장 상태를 다시 확인
     *
     * <p>동일한 Provider 계정의 다른 요청이 먼저 성공했다면
     * 그 요청이 생성한 사용자를 반환합니다.</p>
     *
     * <p>Provider 연결은 없고 동일 이메일 사용자만 존재한다면
     * 로컬 계정 또는 다른 로그인 경로와의 충돌이므로 자동 연결하지 않고
     * 명시적인 계정 연결을 요구합니다.</p>
     */
    private User resolveAfterCreationConflict(
        OAuthProvider provider,
        String providerUserId,
        String normalizedEmail,
        DataIntegrityViolationException cause
    ) {
        return oauthAccountRepository
            .findByProviderAndProviderUserId(
                provider,
                providerUserId
            )
            .map(OAuthAccount::getUser)
            .orElseThrow(() -> {
                if (userRepository.existsByEmail(
                    normalizedEmail
                )) {
                    return authenticationException(
                        ACCOUNT_LINK_REQUIRED,
                        "동일 이메일의 기존 사용자에게 명시적인 계정 연결이 필요합니다.",
                        cause
                    );
                }

                return authenticationException(
                    ACCOUNT_CREATION_CONFLICT,
                    "OAuth 사용자 생성 중 데이터 충돌이 발생했습니다.",
                    cause
                );
            });
    }

    private void validateProviderIdentity(
        OAuthProvider provider,
        String providerUserId
    ) {
        if (provider == null) {
            throw authenticationException(
                "invalid_oauth_provider",
                "OAuth Provider가 없습니다.",
                null
            );
        }

        if (providerUserId == null
            || providerUserId.isBlank()) {
            throw authenticationException(
                "invalid_provider_user_id",
                "OAuth Provider 사용자 ID가 없습니다.",
                null
            );
        }

        if (providerUserId.length() > 255) {
            throw authenticationException(
                "invalid_provider_user_id",
                "OAuth Provider 사용자 ID가 너무 깁니다.",
                null
            );
        }
    }

    private String normalizeAndValidateEmail(
        String email
    ) {
        if (email == null || email.isBlank()) {
            throw authenticationException(
                "oauth_email_required",
                "신규 OAuth 사용자 생성에 이메일이 필요합니다.",
                null
            );
        }

        String normalizedEmail =
            email.strip()
                .toLowerCase(Locale.ROOT);

        if (normalizedEmail.length() > MAX_EMAIL_LENGTH) {
            throw authenticationException(
                "invalid_oauth_email",
                "OAuth 사용자 이메일이 너무 깁니다.",
                null
            );
        }

        return normalizedEmail;
    }

    private String validateName(
        String name
    ) {
        if (name == null || name.isBlank()) {
            throw authenticationException(
                "oauth_name_required",
                "신규 OAuth 사용자 생성에 이름이 필요합니다.",
                null
            );
        }

        String normalizedName = name.strip();

        if (normalizedName.length() > MAX_NAME_LENGTH) {
            throw authenticationException(
                "invalid_oauth_name",
                "OAuth 사용자 이름이 너무 깁니다.",
                null
            );
        }

        return normalizedName;
    }

    private String validateProfileImageUrl(
        String profileImageUrl
    ) {
        if (profileImageUrl == null
            || profileImageUrl.isBlank()) {
            return null;
        }

        String normalizedUrl =
            profileImageUrl.strip();

        if (normalizedUrl.length()
            > MAX_PROFILE_IMAGE_URL_LENGTH) {
            throw authenticationException(
                "invalid_oauth_profile_image",
                "OAuth 프로필 이미지 URL이 너무 깁니다.",
                null
            );
        }

        return normalizedUrl;
    }

    private OAuth2AuthenticationException authenticationException(
        String errorCode,
        String description,
        Throwable cause
    ) {
        OAuth2Error error =
            new OAuth2Error(
                errorCode,
                description,
                null
            );

        if (cause == null) {
            return new OAuth2AuthenticationException(
                error,
                description
            );
        }

        return new OAuth2AuthenticationException(
            error,
            description,
            cause
        );
    }
}
