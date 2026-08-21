package com.mopl.user.security.oauth;

import com.mopl.user.entity.OAuthProvider;
import com.mopl.user.entity.User;
import com.mopl.user.service.OAuthUserProvisioningService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

/**
 * Google OIDC 인증 결과를 모두의 플리 사용자 인증 정보로 변환
 *
 * <p>Spring Security의 {@link OidcUserService}에 Google ID Token과
 * UserInfo 검증을 위임한 뒤, 검증된 {@code sub}를 Google 사용자
 * 고유 식별자로 사용합니다.</p>
 *
 * <p>Google 네트워크 요청은 DB 트랜잭션 밖에서 완료하고,
 * 사용자 조회·생성은 {@link OAuthUserProvisioningService}의
 * 별도 트랜잭션에서 처리합니다.</p>
 */
@Service
public class GoogleOidcUserService
    implements OAuth2UserService<OidcUserRequest, OidcUser> {

    public static final String INVALID_GOOGLE_SUBJECT =
        "invalid_google_subject";

    public static final String GOOGLE_EMAIL_REQUIRED =
        "google_email_required";

    public static final String GOOGLE_EMAIL_NOT_VERIFIED =
        "google_email_not_verified";

    public static final String GOOGLE_ACCOUNT_LOCKED =
        "google_account_locked";

    private static final String GOOGLE_REGISTRATION_ID =
        "google";

    private static final String DEFAULT_GOOGLE_USER_NAME =
        "Google 사용자";

    private final OAuthUserProvisioningService
        provisioningService;

    private final OAuth2UserService<OidcUserRequest, OidcUser>
        delegate;

    /**
     * 운영 환경에서 사용하는 생성자
     *
     * <p>Google ID Token과 UserInfo 처리는 Spring Security의
     * 표준 OIDC 구현체에 위임합니다.</p>
     */
    @Autowired
    public GoogleOidcUserService(
        OAuthUserProvisioningService provisioningService
    ) {
        this(
            provisioningService,
            new OidcUserService()
        );
    }

    /**
     * 테스트에서 외부 Google 요청을 대체할 delegate를 주입
     */
    GoogleOidcUserService(
        OAuthUserProvisioningService provisioningService,
        OAuth2UserService<OidcUserRequest, OidcUser> delegate
    ) {
        this.provisioningService =
            provisioningService;
        this.delegate = delegate;
    }

    /**
     * Google OIDC 사용자를 모두의 플리 Principal로 변환
     *
     * <ol>
     *     <li>요청의 registrationId가 google인지 확인합니다.</li>
     *     <li>Spring Security OidcUserService로 인증 정보를 조회합니다.</li>
     *     <li>Google sub, 이메일 및 이메일 검증 여부를 확인합니다.</li>
     *     <li>연결된 MOPL 사용자를 조회하거나 신규 생성합니다.</li>
     *     <li>잠긴 계정은 인증을 거부합니다.</li>
     *     <li>MoplOidcUser를 반환합니다.</li>
     * </ol>
     */
    @Override
    public OidcUser loadUser(
        OidcUserRequest userRequest
    ) throws OAuth2AuthenticationException {
        validateGoogleRequest(userRequest);

        /*
         * 이 호출에서 Google UserInfo Endpoint 외부 통신이 발생할 수 있다.
         * 현재 메서드에는 @Transactional을 적용하지 않는다.
         */
        OidcUser googleUser =
            delegate.loadUser(userRequest);

        if (googleUser == null) {
            throw authenticationException(
                "google_user_info_missing",
                "Google OIDC 사용자 정보가 없습니다."
            );
        }

        String providerUserId =
            requireText(
                googleUser.getSubject(),
                INVALID_GOOGLE_SUBJECT,
                "Google OIDC subject가 없습니다."
            );

        String email =
            requireText(
                googleUser.getEmail(),
                GOOGLE_EMAIL_REQUIRED,
                "Google 이메일이 없습니다."
            );

        /*
         * 신규 계정 생성과 기존 계정 연결 후보 판단에 사용하는 이메일은
         * Google이 소유권을 검증한 경우에만 신뢰합니다.
         */
        if (!Boolean.TRUE.equals(
            googleUser.getEmailVerified()
        )) {
            throw authenticationException(
                GOOGLE_EMAIL_NOT_VERIFIED,
                "검증되지 않은 Google 이메일입니다."
            );
        }

        String name =
            normalizeName(
                googleUser.getFullName()
            );

        String profileImageUrl =
            profileImageUrl(
                googleUser.getPicture()
            );

        User user =
            provisioningService.resolveOrCreate(
                OAuthProvider.GOOGLE,
                providerUserId,
                email,
                name,
                profileImageUrl
            );

        /*
         * 이메일·비밀번호 로그인과 동일하게 잠긴 사용자는
         * OAuth 로그인에서도 인증에 성공할 수 없다.
         */
        if (user.isLocked()) {
            throw new LockedException(
                GOOGLE_ACCOUNT_LOCKED
            );
        }

        return new MoplOidcUser(
            user.getId(),
            user.getEmail(),
            user.getRole(),
            OAuthProvider.GOOGLE,
            providerUserId,
            googleUser.getAttributes(),
            googleUser.getIdToken(),
            googleUser.getUserInfo()
        );
    }

    private void validateGoogleRequest(
        OidcUserRequest userRequest
    ) {
        if (userRequest == null
            || userRequest.getClientRegistration() == null
            || !GOOGLE_REGISTRATION_ID.equals(
            userRequest
                .getClientRegistration()
                .getRegistrationId()
        )) {
            throw authenticationException(
                "unsupported_oidc_provider",
                "지원하지 않는 OIDC Provider입니다."
            );
        }
    }

    private String requireText(
        String value,
        String errorCode,
        String description
    ) {
        if (value == null || value.isBlank()) {
            throw authenticationException(
                errorCode,
                description
            );
        }

        return value.strip();
    }

    /**
     * Google 이름은 선택 정보이므로 누락되면 서비스 기본 이름을 사용
     */
    private String normalizeName(
        String name
    ) {
        if (name == null || name.isBlank()) {
            return DEFAULT_GOOGLE_USER_NAME;
        }

        return name.strip();
    }

    private String profileImageUrl(
        String picture
    ) {
        if (picture == null || picture.isBlank()) {
            return null;
        }

        return picture.strip();
    }

    private OAuth2AuthenticationException
    authenticationException(
        String errorCode,
        String description
    ) {
        return new OAuth2AuthenticationException(
            new OAuth2Error(
                errorCode,
                description,
                null
            ),
            description
        );
    }
}
