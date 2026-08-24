package com.mopl.user.security.oauth.link;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.entity.OAuthProvider;
import com.mopl.user.entity.User;
import com.mopl.user.service.OAuthAccountManagementService;
import com.mopl.user.service.OAuthUserProvisioningService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Service;

/**
 * Provider 인증이 완료된 OAuth 사용자를 일반 로그인 또는 계정 연결로 분기
 *
 * <p>현재 HTTP 세션에 OAuth 계정 연결 의도가 없으면 기존 OAuth 로그인
 * 흐름에 따라 연결된 사용자를 조회하거나 신규 사용자를 생성합니다.</p>
 *
 * <p>연결 의도가 있으면 신규 사용자를 만들지 않고, 인증된 Provider 계정을
 * 연결 의도에 기록된 기존 MOPL 사용자에게 연결합니다.</p>
 */
@Service
@RequiredArgsConstructor
public class OAuthUserResolutionService {

    public static final String INVALID_LINK_INTENT =
        "invalid_oauth_link_intent";

    public static final String ACCOUNT_LINK_CONFLICT =
        "oauth_account_link_conflict";

    public static final String LINK_TARGET_NOT_FOUND =
        "oauth_link_user_not_found";

    public static final String ACCOUNT_LINK_FAILED =
        "oauth_account_link_failed";

    private final OAuthUserProvisioningService
        provisioningService;

    private final OAuthAccountManagementService
        accountManagementService;

    private final OAuthLinkIntentSessionStore
        linkIntentSessionStore;

    /*
     * OAuth Provider UserService는 Singleton Bean이므로 요청 객체를
     * 직접 보관하지 않고 현재 요청이 필요할 때만 조회
     */
    private final ObjectProvider<HttpServletRequest>
        requestProvider;

    /**
     * Provider 인증 결과를 일반 로그인 또는 계정 연결 흐름으로 처리
     *
     * @param provider 인증을 완료한 OAuth Provider
     * @param providerUserId Provider가 검증해 반환한 사용자 식별자
     * @param email Provider 사용자 이메일, 제공되지 않으면 null
     * @param name Provider 사용자 이름
     * @param profileImageUrl Provider 프로필 이미지 URL
     * @return 인증 또는 계정 연결 대상 MOPL 사용자
     */
    public User resolve(
        OAuthProvider provider,
        String providerUserId,
        String email,
        String name,
        String profileImageUrl
    ) {
        HttpServletRequest request =
            requestProvider.getIfAvailable();

        /*
         * HTTP 요청이 없거나 연결 의도가 없으면 기존 OAuth 로그인 흐름
         *
         * request가 없는 경우는 주로 단위 테스트나 비웹 실행 문맥이며,
         * 계정 연결은 반드시 브라우저 세션을 필요로 하므로 일반 로그인
         * 처리만 허용
         */
        if (request == null
            || !linkIntentSessionStore
            .hasPendingIntent(request)) {
            return provisioningService
                .resolveOrCreate(
                    provider,
                    providerUserId,
                    email,
                    name,
                    profileImageUrl
                );
        }

        /*
         * 연결 의도가 존재했던 요청은 반드시 유효한 의도를 소비해야 한다.
         *
         * 만료 또는 Provider 불일치로 consume()이 빈 결과를 반환한 경우
         * 일반 로그인으로 전환하지 않고 인증을 실패시킨다.
         */
        Optional<OAuthLinkIntent> consumedIntent =
            linkIntentSessionStore.consume(
                request,
                provider
            );

        OAuthLinkIntent linkIntent =
            consumedIntent.orElseThrow(() ->
                authenticationException(
                    INVALID_LINK_INTENT,
                    "OAuth 계정 연결 요청이 만료되었거나 유효하지 않습니다.",
                    null
                )
            );

        try {
            return accountManagementService
                .linkVerifiedAccount(
                    linkIntent,
                    provider,
                    providerUserId
                );
        } catch (BusinessException exception) {
            throw convertLinkException(
                exception
            );
        }
    }

    /**
     * 계정 연결 도메인 예외를 Spring Security OAuth 인증 예외로 변환
     *
     * <p>OAuth UserService 경로에서 BusinessException을 그대로 노출하지
     * 않고 인증 실패 Handler가 일관되게 처리할 수 있도록 변환합니다.</p>
     */
    private OAuth2AuthenticationException
    convertLinkException(
        BusinessException exception
    ) {
        ErrorCode errorCode =
            exception.getErrorCode();

        if (errorCode
            == ErrorCode.OAUTH_ACCOUNT_CONFLICT) {
            return authenticationException(
                ACCOUNT_LINK_CONFLICT,
                "이미 다른 사용자 또는 계정에 연결된 OAuth 계정입니다.",
                exception
            );
        }

        if (errorCode
            == ErrorCode.RESOURCE_NOT_FOUND) {
            return authenticationException(
                LINK_TARGET_NOT_FOUND,
                "OAuth 계정을 연결할 사용자를 찾을 수 없습니다.",
                exception
            );
        }

        return authenticationException(
            ACCOUNT_LINK_FAILED,
            "OAuth 계정을 연결할 수 없습니다.",
            exception
        );
    }

    private OAuth2AuthenticationException
    authenticationException(
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
