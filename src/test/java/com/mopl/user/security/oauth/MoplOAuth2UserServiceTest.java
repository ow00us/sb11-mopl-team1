package com.mopl.user.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * 일반 OAuth2 Provider 요청이 올바른 Provider별 서비스로
 * 전달되는지 검증
 */
@ExtendWith(MockitoExtension.class)
class MoplOAuth2UserServiceTest {

    @Mock
    KakaoOAuth2UserService kakaoOAuth2UserService;

    @Mock
    NaverOAuth2UserService naverOAuth2UserService;

    @Mock
    OAuth2UserRequest userRequest;

    @Mock
    ClientRegistration clientRegistration;

    @Mock
    OAuth2User kakaoUser;

    @Mock
    OAuth2User naverUser;

    MoplOAuth2UserService moplOAuth2UserService;

    @BeforeEach
    void setUp() {
        moplOAuth2UserService =
            new MoplOAuth2UserService(
                kakaoOAuth2UserService,
                naverOAuth2UserService
            );
    }

    @Test
    @DisplayName("Kakao 요청은 Kakao OAuth2 사용자 서비스로 전달한다")
    void loadUser_routesKakaoRequest() {
        // given
        when(userRequest.getClientRegistration())
            .thenReturn(clientRegistration);

        when(clientRegistration.getRegistrationId())
            .thenReturn("kakao");

        when(kakaoOAuth2UserService.loadUser(userRequest))
            .thenReturn(kakaoUser);

        // when
        OAuth2User result =
            moplOAuth2UserService.loadUser(
                userRequest
            );

        // then
        assertThat(result)
            .isSameAs(kakaoUser);

        verify(kakaoOAuth2UserService)
            .loadUser(userRequest);

        verifyNoInteractions(naverOAuth2UserService);
    }

    @Test
    @DisplayName("Naver 요청은 Naver OAuth2 사용자 서비스로 전달한다")
    void loadUser_routesNaverRequest() {
        // given
        when(userRequest.getClientRegistration())
            .thenReturn(clientRegistration);

        when(clientRegistration.getRegistrationId())
            .thenReturn("naver");

        when(naverOAuth2UserService.loadUser(userRequest))
            .thenReturn(naverUser);

        // when
        OAuth2User result =
            moplOAuth2UserService.loadUser(
                userRequest
            );

        // then
        assertThat(result)
            .isSameAs(naverUser);

        verify(naverOAuth2UserService)
            .loadUser(userRequest);

        verifyNoInteractions(kakaoOAuth2UserService);
    }

    @Test
    @DisplayName("지원하지 않는 일반 OAuth2 Provider 요청은 거부한다")
    void loadUser_rejectsUnsupportedProvider() {
        // given
        when(userRequest.getClientRegistration())
            .thenReturn(clientRegistration);

        when(clientRegistration.getRegistrationId())
            .thenReturn("unknown");

        // when & then
        assertUnsupportedProvider(() ->
            moplOAuth2UserService.loadUser(
                userRequest
            )
        );

        verify(kakaoOAuth2UserService, never())
            .loadUser(userRequest);

        verify(naverOAuth2UserService, never())
            .loadUser(userRequest);
    }

    @Test
    @DisplayName("OAuth2 사용자 요청이 null이면 Provider 선택 전에 거부한다")
    void loadUser_rejectsNullRequest() {
        assertUnsupportedProvider(() ->
            moplOAuth2UserService.loadUser(null)
        );

        verifyNoInteractions(
            kakaoOAuth2UserService,
            naverOAuth2UserService
        );
    }

    @Test
    @DisplayName("registrationId가 공백이면 Provider 선택 전에 거부한다")
    void loadUser_rejectsBlankRegistrationId() {
        // given
        when(userRequest.getClientRegistration())
            .thenReturn(clientRegistration);

        when(clientRegistration.getRegistrationId())
            .thenReturn(" ");

        // when & then
        assertUnsupportedProvider(() ->
            moplOAuth2UserService.loadUser(
                userRequest
            )
        );

        verifyNoInteractions(
            kakaoOAuth2UserService,
            naverOAuth2UserService
        );
    }

    /**
     * 미지원 Provider 예외의 공통 계약을 검증
     */
    private void assertUnsupportedProvider(
        ThrowingCallable callable
    ) {
        assertThatThrownBy(callable::call)
            .isInstanceOf(
                OAuth2AuthenticationException.class
            )
            .satisfies(exception -> {
                OAuth2AuthenticationException oauthException =
                    (OAuth2AuthenticationException) exception;

                assertThat(
                    oauthException
                        .getError()
                        .getErrorCode()
                ).isEqualTo(
                    MoplOAuth2UserService
                        .UNSUPPORTED_OAUTH_PROVIDER
                );
            });
    }

    /**
     * AssertJ에 예외 발생 코드를 전달하기 위한 테스트 내부 함수형 인터페이스
     */
    @FunctionalInterface
    private interface ThrowingCallable {

        void call();
    }
}
