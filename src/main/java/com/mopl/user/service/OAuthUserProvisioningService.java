package com.mopl.user.service;

import com.mopl.user.entity.OAuthAccount;
import com.mopl.user.entity.OAuthProvider;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.OAuthAccountRepository;
import com.mopl.user.repository.UserRepository;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 외부 OAuth 계정을 모두의 플리 사용자와 연결하거나 신규 사용자를 생성
 *
 * <p>Provider 사용자 정보 조회는 외부 네트워크 I/O이므로 이 서비스 밖에서
 * 완료합니다. 이 서비스는 조회된 정보를 전달받아 짧은 DB 트랜잭션 안에서
 * User와 OAuthAccount만 처리합니다.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
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
    @Transactional
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

        /*
         * 동일 이메일이 존재하더라도 자동으로 Provider 계정을 연결하지 않는다.
         * 기존 계정 인증 없이 연결하면 계정 탈취로 이어질 수 있다.
         */
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw authenticationException(
                ACCOUNT_LINK_REQUIRED,
                "동일 이메일의 기존 사용자에게 명시적인 계정 연결이 필요합니다.",
                null
            );
        }

        User user = User.builder()
            .email(normalizedEmail)
            .passwordHash(null)
            .name(validatedName)
            .profileImageUrl(validatedProfileImageUrl)
            .role(UserRole.USER)
            .locked(false)
            .build();

        try {
            /*
             * User와 OAuthAccount를 하나의 트랜잭션에서 저장합니다.
             * OAuthAccount 저장이 실패하면 User 생성도 함께 롤백
             */
            User savedUser =
                userRepository.save(user);

            OAuthAccount oauthAccount =
                OAuthAccount.builder()
                    .user(savedUser)
                    .provider(provider)
                    .providerUserId(providerUserId)
                    .build();

            oauthAccountRepository.saveAndFlush(
                oauthAccount
            );

            return savedUser;
        } catch (DataIntegrityViolationException exception) {
            /*
             * 동시에 같은 소셜 계정으로 최초 로그인하거나 같은 이메일로
             * 가입한 경우 DB 유일성 제약이 최종 방어선이 된다.
             */
            throw authenticationException(
                ACCOUNT_CREATION_CONFLICT,
                "OAuth 사용자 생성 중 데이터 충돌이 발생했습니다.",
                exception
            );
        }
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
