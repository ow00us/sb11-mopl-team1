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
 * Naver OAuth2 인증 결과를 모두의 플리 사용자 인증 정보로 변환
 *
 * <p>Naver UserInfo 응답의 {@code response.id}를 변경되지 않는
 * Provider 사용자 식별자로 사용합니다. 닉네임과 프로필 이미지는
 * 선택 정보로 처리합니다.</p>
 *
 * <p>현재 Naver 애플리케이션은 이메일 권한을 사용하지 않으므로 이메일을
 * 계정 식별이나 자동 연결에 사용하지 않습니다. 신규 사용자의 내부 식별
 * 이메일 생성은 {@link OAuthUserResolutionService}에 위임합니다.</p>
 *
 * <p>Naver 외부 네트워크 요청은 DB 트랜잭션 밖에서 완료하고,
 * 사용자 조회·생성은 Provisioning Service의 별도 트랜잭션에서
 * 처리합니다.</p>
 */
@Service
public class NaverOAuth2UserService
    implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    public static final String INVALID_NAVER_USER_ID =
        "invalid_naver_user_id";

    public static final String INVALID_NAVER_USER_INFO =
        "invalid_naver_user_info";

    public static final String NAVER_ACCOUNT_LOCKED =
        "naver_account_locked";

    private static final String NAVER_REGISTRATION_ID =
        "naver";

    private static final String DEFAULT_NAVER_USER_NAME =
        "Naver 사용자";

    private final OAuthUserResolutionService
        userResolutionService;

    private final OAuth2UserService<OAuth2UserRequest, OAuth2User>
        delegate;

    /**
     * 제한 시간이 적용된 Naver OAuth2 delegate를 주입받아 사용
     *
     * @param userResolutionService OAuth 로그인·계정 연결 분기 서비스
     * @param delegate Naver UserInfo 조회 delegate
     */
    public NaverOAuth2UserService(
        OAuthUserResolutionService userResolutionService,
        @Qualifier("naverOAuth2UserDelegate")
        OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate
    ) {
        this.userResolutionService = userResolutionService;
        this.delegate = delegate;
    }

    /**
     * Naver OAuth2 사용자를 모두의 플리 Principal로 변환
     *
     * <ol>
     *     <li>요청의 registrationId가 naver인지 확인합니다.</li>
     *     <li>제한 시간이 적용된 delegate로 UserInfo를 조회합니다.</li>
     *     <li>response 객체에서 id, 닉네임, 프로필 이미지를 추출합니다.</li>
     *     <li>연결된 MOPL 사용자를 조회하거나 신규 생성합니다.</li>
     *     <li>잠긴 계정의 인증을 거부합니다.</li>
     *     <li>MoplOAuth2User를 반환합니다.</li>
     * </ol>
     *
     * @param userRequest Naver OAuth2 사용자 요청
     * @return MOPL 공통 OAuth2 Principal
     */
    @Override
    public OAuth2User loadUser(
        OAuth2UserRequest userRequest
    ) throws OAuth2AuthenticationException {
        validateNaverRequest(userRequest);

        /*
         * Naver UserInfo Endpoint 외부 통신이 발생
         * DB 커넥션을 외부 통신 동안 점유하지 않도록
         * 이 클래스에는 @Transactional을 적용하지 않는다.
         */
        OAuth2User naverUser =
            delegate.loadUser(userRequest);

        if (naverUser == null) {
            throw authenticationException(
                "naver_user_info_missing",
                "Naver OAuth2 사용자 정보가 없습니다."
            );
        }

        Map<String, Object> attributes =
            naverUser.getAttributes();

        if (attributes == null) {
            throw authenticationException(
                "naver_user_info_missing",
                "Naver OAuth2 사용자 attributes가 없습니다."
            );
        }

        /*
         * Naver UserInfo 응답은 id와 프로필 정보를 최상위가 아닌
         * response 객체 안에 담아 반환
         */
        Map<?, ?> response =
            validateUserInfoResponse(attributes);

        String providerUserId =
            providerUserId(
                response.get("id")
            );

        String name =
            firstText(
                response.get("nickname"),
                response.get("name"),
                DEFAULT_NAVER_USER_NAME
            );

        String profileImageUrl =
            firstTextOrNull(
                response.get("profile_image")
            );

        /*
         * Naver 인증 요청에는 별도 scope를 전송하지 않고,
         * 프로필 제공 항목은 Naver Developers 권한과 사용자 동의로 관리
         *
         * 현재 MOPL 정책에서는 Provider 이메일을 계정 식별이나
         * 기존 로컬 계정 자동 연결에 사용하지 않으므로 null을 전달
         * 신규 사용자는 Provisioning Service에서 발송 불가능한
         * 내부 식별 이메일을 생성
         */
        User user =
            userResolutionService.resolve(
                OAuthProvider.NAVER,
                providerUserId,
                null,
                name,
                profileImageUrl
            );

        /*
         * 이메일·비밀번호 로그인과 동일하게 잠긴 사용자는
         * Naver 로그인에서도 인증에 성공할 수 없다.
         */
        if (user.isLocked()) {
            throw new LockedException(
                NAVER_ACCOUNT_LOCKED
            );
        }

        return new MoplOAuth2User(
            user.getId(),
            user.getEmail(),
            user.getRole(),
            OAuthProvider.NAVER,
            providerUserId,
            attributes
        );
    }

    /**
     * Naver용 OAuth2 요청인지 확인
     */
    private void validateNaverRequest(
        OAuth2UserRequest userRequest
    ) {
        if (
            userRequest == null
                || userRequest.getClientRegistration() == null
                || !NAVER_REGISTRATION_ID.equals(
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
     * Naver UserInfo 응답의 성공 코드와 response 객체를 검증
     *
     * <p>공식 응답 계약에서 resultcode가 00인 경우만 성공입니다.
     * HTTP 요청 자체가 성공했더라도 Naver가 실패 resultcode를 반환하거나
     * response 객체가 누락되면 사용자 생성으로 진행하지 않습니다.</p>
     *
     * @param attributes Naver UserInfo 최상위 응답
     * @return 검증된 response 객체
     */
    private Map<?, ?> validateUserInfoResponse(
        Map<String, Object> attributes
    ) {
        if (!"00".equals(attributes.get("resultcode"))) {
            throw authenticationException(
                INVALID_NAVER_USER_INFO,
                "Naver 사용자 정보 조회 결과가 유효하지 않습니다."
            );
        }

        Object responseValue =
            attributes.get("response");

        if (!(responseValue instanceof Map<?, ?> response)) {
            throw authenticationException(
                INVALID_NAVER_USER_INFO,
                "Naver 사용자 정보 response가 없습니다."
            );
        }

        return response;
    }

    /**
     * Naver UserInfo의 response.id를 Provider 사용자 식별자로 변환
     */
    private String providerUserId(
        Object value
    ) {
        if (value instanceof String text
            && !text.isBlank()) {
            return text.strip();
        }

        throw authenticationException(
            INVALID_NAVER_USER_ID,
            "Naver 사용자 ID가 없습니다."
        );
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

    /**
     * 외부 사용자 정보 검증 실패를 OAuth2 인증 실패로 변환
     */
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
