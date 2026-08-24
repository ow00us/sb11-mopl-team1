package com.mopl.user.security.oauth;

import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

/**
 * 일반 OAuth2 Provider의 사용자 정보 조회 서비스를 선택하는 공통 라우터
 *
 * <p>Spring Security의 OAuth2 Login 설정에서 {@code userService}는
 * 모든 일반 OAuth2 Provider에 공통 적용됩니다. 특정 Provider 서비스를
 * 직접 등록하면 다른 Provider 요청도 같은 서비스로 전달되므로,
 * registrationId에 따라 Provider별 서비스를 선택합니다.</p>
 *
 * <p>Google은 OIDC Provider이므로 이 라우터를 거치지 않고
 * {@link GoogleOidcUserService}로 별도 처리됩니다.</p>
 */
@Service
public class MoplOAuth2UserService
    implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    public static final String UNSUPPORTED_OAUTH_PROVIDER =
        "unsupported_oauth_provider";

    private static final String KAKAO_REGISTRATION_ID =
        "kakao";

    private static final String NAVER_REGISTRATION_ID =
        "naver";

    private final KakaoOAuth2UserService
        kakaoOAuth2UserService;

    private final NaverOAuth2UserService
        naverOAuth2UserService;

    /**
     * 일반 OAuth2 Provider 라우터를 생성
     *
     * @param kakaoOAuth2UserService Kakao 사용자 정보 조회 서비스
     * @param naverOAuth2UserService Naver 사용자 정보 조회 서비스
     */
    public MoplOAuth2UserService(
        KakaoOAuth2UserService kakaoOAuth2UserService,
        NaverOAuth2UserService naverOAuth2UserService
    ) {
        this.kakaoOAuth2UserService =
            kakaoOAuth2UserService;
        this.naverOAuth2UserService =
            naverOAuth2UserService;
    }

    /**
     * registrationId에 해당하는 Provider별 서비스로 요청을 전달
     *
     * @param userRequest OAuth2 사용자 요청
     * @return MOPL 공통 OAuth2 Principal
     */
    @Override
    public OAuth2User loadUser(
        OAuth2UserRequest userRequest
    ) throws OAuth2AuthenticationException {
        String registrationId =
            registrationId(userRequest);

        if (KAKAO_REGISTRATION_ID.equals(
            registrationId
        )) {
            return kakaoOAuth2UserService.loadUser(
                userRequest
            );
        }

        if (NAVER_REGISTRATION_ID.equals(
            registrationId
        )) {
            return naverOAuth2UserService.loadUser(
                userRequest
            );
        }

        throw authenticationException(
            "지원하지 않는 OAuth Provider입니다: "
                + registrationId
        );
    }

    /**
     * 요청에서 registrationId를 안전하게 추출
     */
    private String registrationId(
        OAuth2UserRequest userRequest
    ) {
        if (
            userRequest == null
                || userRequest.getClientRegistration() == null
        ) {
            throw authenticationException(
                "OAuth Provider 정보가 없습니다."
            );
        }

        String registrationId =
            userRequest
                .getClientRegistration()
                .getRegistrationId();

        if (
            registrationId == null
                || registrationId.isBlank()
        ) {
            throw authenticationException(
                "OAuth Provider 식별자가 없습니다."
            );
        }

        return registrationId.strip();
    }

    /**
     * 지원하지 않는 Provider 요청을 OAuth2 인증 실패로 변환
     */
    private OAuth2AuthenticationException
    authenticationException(
        String description
    ) {
        return new OAuth2AuthenticationException(
            new OAuth2Error(
                UNSUPPORTED_OAUTH_PROVIDER,
                description,
                null
            ),
            description
        );
    }
}
