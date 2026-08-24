package com.mopl.user.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;


class OAuth2UserInfoClientConfigTest {

    private final OAuth2UserInfoClientConfig config =
        new OAuth2UserInfoClientConfig();

    @Test
    @DisplayName("정상 제한 시간으로 Google OIDC delegate를 생성한다")
    void createDelegate_success() {
        assertThat(
            config.googleOidcUserDelegate(
                Duration.ofSeconds(3),
                Duration.ofSeconds(5)
            )
        ).isNotNull();
    }

    @Test
    @DisplayName("정상 제한 시간으로 Kakao OAuth2 delegate를 생성한다")
    void createKakaoDelegate_success() {
        assertThat(
            config.kakaoOAuth2UserDelegate(
                Duration.ofSeconds(3),
                Duration.ofSeconds(5)
            )
        ).isNotNull();
    }

    @Test
    @DisplayName("연결 제한 시간이 0이면 생성을 거부한다")
    void createDelegate_failWhenConnectTimeoutIsZero() {
        assertThatThrownBy(() ->
            config.googleOidcUserDelegate(
                Duration.ZERO,
                Duration.ofSeconds(5)
            )
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("0보다 커야");
    }

    @Test
    @DisplayName("응답 제한 시간이 음수이면 생성을 거부한다")
    void createDelegate_failWhenReadTimeoutIsNegative() {
        assertThatThrownBy(() ->
            config.googleOidcUserDelegate(
                Duration.ofSeconds(3),
                Duration.ofSeconds(-1)
            )
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("0보다 커야");
    }

    @Test
    @DisplayName("제한 시간이 1ms 미만이면 생성을 거부한다")
    void createDelegate_failWhenTimeoutIsLessThanOneMillisecond() {
        assertThatThrownBy(() ->
            config.googleOidcUserDelegate(
                Duration.ofNanos(1),
                Duration.ofSeconds(5)
            )
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("최소 1ms");
    }

    @Test
    @DisplayName("제한 시간이 int 밀리초 범위를 넘으면 생성을 거부한다")
    void createDelegate_failWhenTimeoutExceedsIntegerRange() {
        assertThatThrownBy(() ->
            config.googleOidcUserDelegate(
                Duration.ofMillis(
                    (long) Integer.MAX_VALUE + 1
                ),
                Duration.ofSeconds(5)
            )
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("지원 범위를 초과");
    }

    @Test
    @DisplayName("Google UserInfo 응답이 지연되면 제한 시간 후 인증에 실패한다")
    void googleUserInfo_failWhenResponseExceedsReadTimeout()
        throws IOException {

        HttpServer server =
            HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
            );

        ExecutorService executor =
            Executors.newSingleThreadExecutor();

        server.setExecutor(executor);

        server.createContext(
            "/userinfo",
            exchange -> {
                try {
                    /*
                     * 설정한 read timeout보다 응답을 늦게 반환하여
                     * 실제 HTTP Client 제한 시간이 작동하도록 한다.
                     */
                    Thread.sleep(500);

                    byte[] response =
                        """
                        {
                          "sub": "google-sub-123",
                          "email": "user@example.com",
                          "email_verified": true,
                          "name": "Google 사용자"
                        }
                        """
                            .getBytes(StandardCharsets.UTF_8);

                    exchange
                        .getResponseHeaders()
                        .set(
                            "Content-Type",
                            "application/json"
                        );

                    exchange.sendResponseHeaders(
                        200,
                        response.length
                    );

                    exchange
                        .getResponseBody()
                        .write(response);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                    /*
                     * 클라이언트가 timeout으로 연결을 먼저 종료하면
                     * 지연 서버의 응답 쓰기에서 IOException이 발생할 수 있다.
                     */
                } finally {
                    exchange.close();
                }
            }
        );

        server.start();

        try {
            String userInfoUri =
                "http://127.0.0.1:"
                    + server.getAddress().getPort()
                    + "/userinfo";

            OAuth2UserService<OidcUserRequest, OidcUser>
                delegate =
                config.googleOidcUserDelegate(
                    Duration.ofSeconds(1),
                    Duration.ofMillis(50)
                );

            OidcUserRequest userRequest =
                createOidcUserRequest(userInfoUri);

            assertThatThrownBy(() ->
                delegate.loadUser(userRequest)
            )
                .isInstanceOf(
                    OAuth2AuthenticationException.class
                )
                .hasRootCauseInstanceOf(
                    SocketTimeoutException.class
                );
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Kakao UserInfo 응답이 지연되면 제한 시간 후 인증에 실패한다")
    void kakaoUserInfo_failWhenResponseExceedsReadTimeout()
        throws IOException {

        HttpServer server =
            HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
            );

        ExecutorService executor =
            Executors.newSingleThreadExecutor();

        server.setExecutor(executor);

        server.createContext(
            "/userinfo",
            exchange -> {
                try {
                    /*
                     * Kakao UserInfo 응답을 설정한 read timeout보다
                     * 늦게 반환하여 HTTP Client 제한 시간을 검증
                     */
                    Thread.sleep(500);

                    byte[] response =
                        """
                        {
                          "id": 123456789,
                          "properties": {
                            "nickname": "Kakao 사용자",
                            "profile_image": "https://example.com/profile.png"
                          }
                        }
                        """
                            .getBytes(StandardCharsets.UTF_8);

                    exchange
                        .getResponseHeaders()
                        .set(
                            "Content-Type",
                            "application/json"
                        );

                    exchange.sendResponseHeaders(
                        200,
                        response.length
                    );

                    exchange
                        .getResponseBody()
                        .write(response);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                    /*
                     * 클라이언트가 timeout으로 연결을 먼저 종료하면
                     * 지연 서버의 응답 쓰기에서 IOException이 발생할 수 있다.
                     */
                } finally {
                    exchange.close();
                }
            }
        );

        server.start();

        try {
            String userInfoUri =
                "http://127.0.0.1:"
                    + server.getAddress().getPort()
                    + "/userinfo";

            OAuth2UserService<OAuth2UserRequest, OAuth2User>
                delegate =
                config.kakaoOAuth2UserDelegate(
                    Duration.ofSeconds(1),
                    Duration.ofMillis(50)
                );

            OAuth2UserRequest userRequest =
                createKakaoOAuth2UserRequest(userInfoUri);

            assertThatThrownBy(() ->
                delegate.loadUser(userRequest)
            )
                .isInstanceOf(
                    OAuth2AuthenticationException.class
                )
                .hasRootCauseInstanceOf(
                    SocketTimeoutException.class
                );
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private OidcUserRequest createOidcUserRequest(
        String userInfoUri
    ) {
        ClientRegistration clientRegistration =
            ClientRegistration
                .withRegistrationId("google")
                .clientId("test-google-client-id")
                .clientSecret("test-google-client-secret")
                .clientAuthenticationMethod(
                    ClientAuthenticationMethod.CLIENT_SECRET_BASIC
                )
                .authorizationGrantType(
                    AuthorizationGrantType.AUTHORIZATION_CODE
                )
                .redirectUri(
                    "http://localhost/login/oauth2/code/google"
                )
                .scope(
                    "openid",
                    "profile",
                    "email"
                )
                .authorizationUri(
                    "https://accounts.google.com/o/oauth2/v2/auth"
                )
                .tokenUri(
                    "https://oauth2.googleapis.com/token"
                )
                .jwkSetUri(
                    "https://www.googleapis.com/oauth2/v3/certs"
                )
                .userInfoUri(userInfoUri)
                .userNameAttributeName("sub")
                .clientName("Google")
                .build();

        Instant now = Instant.now();

        OidcIdToken idToken =
            new OidcIdToken(
                "masked-test-id-token",
                now,
                now.plusSeconds(300),
                java.util.Map.of(
                    "sub",
                    "google-sub-123"
                )
            );

        OAuth2AccessToken accessToken =
            new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "masked-test-access-token",
                now,
                now.plusSeconds(300),
                Set.of(
                    "openid",
                    "profile",
                    "email"
                )
            );

        return new OidcUserRequest(
            clientRegistration,
            accessToken,
            idToken
        );
    }

    private OAuth2UserRequest createKakaoOAuth2UserRequest(
        String userInfoUri
    ) {
        ClientRegistration clientRegistration =
            ClientRegistration
                .withRegistrationId("kakao")
                .clientId("test-kakao-client-id")
                .clientSecret("test-kakao-client-secret")
                .clientAuthenticationMethod(
                    ClientAuthenticationMethod.CLIENT_SECRET_POST
                )
                .authorizationGrantType(
                    AuthorizationGrantType.AUTHORIZATION_CODE
                )
                .redirectUri(
                    "http://localhost/login/oauth2/code/kakao"
                )
                .scope(
                    "profile_nickname",
                    "profile_image"
                )
                .authorizationUri(
                    "https://kauth.kakao.com/oauth/authorize"
                )
                .tokenUri(
                    "https://kauth.kakao.com/oauth/token"
                )
                .userInfoUri(userInfoUri)
                .userNameAttributeName("id")
                .clientName("Kakao")
                .build();

        Instant now = Instant.now();

        OAuth2AccessToken accessToken =
            new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "masked-test-kakao-access-token",
                now,
                now.plusSeconds(300),
                Set.of(
                    "profile_nickname",
                    "profile_image"
                )
            );

        return new OAuth2UserRequest(
            clientRegistration,
            accessToken
        );
    }
}
