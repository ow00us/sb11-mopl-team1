package com.mopl.user.security.oauth;

import com.mopl.user.entity.OAuthProvider;
import com.mopl.user.entity.User;
import com.mopl.user.security.oauth.link.OAuthUserResolutionService;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

/**
 * Kakao OAuth2 인증 결과를 모두의 플리 사용자 인증 정보로 변환
 *
 * <p>Kakao UserInfo 응답의 {@code id}를 변경되지 않는 Provider 사용자
 * 식별자로 사용합니다. 닉네임과 프로필 이미지는 선택 정보로 처리합니다.</p>
 *
 * <p>현재 Kakao 애플리케이션은 이메일 권한을 사용하지 않으므로 이메일을
 * 계정 식별이나 자동 연결에 사용하지 않습니다. 신규 사용자의 내부 식별
 * 이메일 생성은 {@link OAuthUserResolutionService}에 위임합니다.</p>
 *
 * <p>Kakao 외부 네트워크 요청은 DB 트랜잭션 밖에서 완료하고,
 * 사용자 조회·생성은 Provisioning Service의 별도 트랜잭션에서
 * 처리합니다.</p>
 */
@Service
public class KakaoOAuth2UserService
    implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    public static final String INVALID_KAKAO_USER_ID =
        "invalid_kakao_user_id";

    public static final String KAKAO_ACCOUNT_LOCKED =
        "kakao_account_locked";

    private static final String KAKAO_REGISTRATION_ID =
        "kakao";

    private static final String DEFAULT_KAKAO_USER_NAME =
        "Kakao 사용자";

    private final OAuthUserResolutionService userResolutionService;

    private final OAuth2UserService<OAuth2UserRequest, OAuth2User>
        delegate;

    /**
     * 제한 시간이 적용된 Kakao OAuth2 delegate를 주입받아 사용
     *
     * @param userResolutionService OAuth 로그인·계정 연결 분기 서비스
     * @param delegate Kakao UserInfo 조회 delegate
     */
    public KakaoOAuth2UserService(
        OAuthUserResolutionService userResolutionService,
        @Qualifier("kakaoOAuth2UserDelegate")
        OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate
    ) {
        this.userResolutionService = userResolutionService;
        this.delegate = delegate;
    }

    /**
     * Kakao OAuth2 사용자를 모두의 플리 Principal로 변환
     *
     * <ol>
     *     <li>요청의 registrationId가 kakao인지 확인합니다.</li>
     *     <li>제한 시간이 적용된 delegate로 UserInfo를 조회합니다.</li>
     *     <li>Kakao id, 닉네임, 프로필 이미지를 추출합니다.</li>
     *     <li>연결된 MOPL 사용자를 조회하거나 신규 생성합니다.</li>
     *     <li>잠긴 계정의 인증을 거부합니다.</li>
     *     <li>MoplOAuth2User를 반환합니다.</li>
     * </ol>
     *
     * @param userRequest Kakao OAuth2 사용자 요청
     * @return MOPL 공통 OAuth2 Principal
     */
    @Override
    public OAuth2User loadUser(
        OAuth2UserRequest userRequest
    ) throws OAuth2AuthenticationException {
        validateKakaoRequest(userRequest);

        /*
         * Kakao UserInfo Endpoint 외부 통신이 발생
         * DB 커넥션을 외부 통신 동안 점유하지 않도록
         * 이 클래스에는 @Transactional을 적용하지 않는다.
         */
        OAuth2User kakaoUser =
            delegate.loadUser(userRequest);

        if (kakaoUser == null) {
            throw authenticationException(
                "kakao_user_info_missing",
                "Kakao OAuth2 사용자 정보가 없습니다."
            );
        }

        Map<String, Object> attributes =
            kakaoUser.getAttributes();

        if (attributes == null) {
            throw authenticationException(
                "kakao_user_info_missing",
                "Kakao OAuth2 사용자 attributes가 없습니다."
            );
        }

        String providerUserId =
            providerUserId(
                attributes.get("id")
            );

        Map<?, ?> properties =
            nestedMap(
                attributes.get("properties")
            );

        Map<?, ?> kakaoAccount =
            nestedMap(
                attributes.get("kakao_account")
            );

        Map<?, ?> profile =
            nestedMap(
                kakaoAccount.get("profile")
            );

        String name =
            firstText(
                properties.get("nickname"),
                profile.get("nickname"),
                DEFAULT_KAKAO_USER_NAME
            );

        String profileImageUrl =
            firstTextOrNull(
                properties.get("profile_image"),
                profile.get("profile_image_url")
            );

        /*
         * 현재 Kakao 애플리케이션은 이메일 동의 항목을 사용하지 않는다.
         * Provider 이메일만으로 기존 로컬 계정을 자동 연결하는 것도
         * 금지하므로 email에는 null을 전달
         *
         * 신규 사용자는 Provisioning Service에서 발송 불가능한
         * 내부 식별 이메일을 생성
         */
        User user =
            userResolutionService.resolve(
                OAuthProvider.KAKAO,
                providerUserId,
                null,
                name,
                profileImageUrl
            );

        /*
         * 이메일·비밀번호 로그인과 동일하게 잠긴 사용자는
         * Kakao 로그인에서도 인증에 성공할 수 없다.
         */
        if (user.isLocked()) {
            throw new LockedException(
                KAKAO_ACCOUNT_LOCKED
            );
        }

        return new MoplOAuth2User(
            user.getId(),
            user.getEmail(),
            user.getRole(),
            OAuthProvider.KAKAO,
            providerUserId,
            attributes
        );
    }

    /**
     * Kakao용 OAuth2 요청인지 확인
     */
    private void validateKakaoRequest(
        OAuth2UserRequest userRequest
    ) {
        if (
            userRequest == null
                || userRequest.getClientRegistration() == null
                || !KAKAO_REGISTRATION_ID.equals(
                userRequest
                    .getClientRegistration()
                    .getRegistrationId()
            )
        ) {
            throw authenticationException(
                "unsupported_oauth_provider",
                "지원하지 않는 OAuth Provider입니다."
            );
        }
    }

    /**
     * Kakao UserInfo의 id를 Provider 사용자 식별자로 변환
     *
     * <p>Kakao id는 JSON Number로 전달되므로 Java에서는 주로
     * Long 또는 Integer로 역직렬화됩니다. 테스트나 응답 변환 방식에 따라
     * String으로 전달되는 경우도 허용합니다.</p>
     */
    private String providerUserId(
        Object value
    ) {
        if (value instanceof Number number) {
            return number.toString();
        }

        if (value instanceof String text
            && !text.isBlank()) {
            return text.strip();
        }

        throw authenticationException(
            INVALID_KAKAO_USER_ID,
            "Kakao 사용자 ID가 없습니다."
        );
    }

    /**
     * 외부 응답의 중첩 객체를 읽기 전용 조회 용도로 변환
     */
    private Map<?, ?> nestedMap(
        Object value
    ) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }

        return Map.of();
    }

    /**
     * 후보 값 중 처음 발견된 문자열을 반환하고, 없으면 기본값 반환
     */
    private String firstText(
        Object first,
        Object second,
        String defaultValue
    ) {
        String resolved =
            firstTextOrNull(
                first,
                second
            );

        return resolved == null
            ? defaultValue
            : resolved;
    }

    /**
     * 후보 값 중 처음 발견된 비어 있지 않은 문자열을 반환
     */
    private String firstTextOrNull(
        Object... values
    ) {
        for (Object value : values) {
            if (value instanceof String text
                && !text.isBlank()) {
                return text.strip();
            }
        }

        return null;
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
