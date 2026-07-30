package com.mopl.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Authorization 헤더의 JWT를 Spring Security 인증 정보로 변환하는 필터를 검증
 */
class JwtAuthenticationFilterTest {

    private JwtProvider jwtProvider;
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setIssuer("mopl");
        jwtProperties.setSecret(
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        );
        jwtProperties.setAccessTokenExpiration(Duration.ofMinutes(30));

        jwtProvider = new JwtProviderImpl(jwtProperties);
        jwtAuthenticationFilter = new JwtAuthenticationFilter(jwtProvider);
    }

    @AfterEach
    void tearDown() {
        // 테스트 사이에 이전 인증 정보가 남지 않도록 SecurityContext를 비움
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("유효한 Bearer 토큰이 있으면 SecurityContext에 인증 정보를 저장한다")
    void doFilterInternal_success() throws Exception {
        // given
        UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        String token = jwtProvider.createAccessToken(userId, "USER");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);

        // when
        jwtAuthenticationFilter.doFilter(
            request,
            new MockHttpServletResponse(),
            new MockFilterChain()
        );

        // then
        Authentication authentication =
            SecurityContextHolder.getContext().getAuthentication();

        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo(userId.toString());
        assertThat(authentication.getAuthorities())
            .extracting(authority -> authority.getAuthority())
            .containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("유효하지 않은 Bearer 토큰이면 인증 정보를 저장하지 않는다")
    void doFilterInternal_fail_whenTokenIsInvalid() throws Exception {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer invalid-token");

        // when
        jwtAuthenticationFilter.doFilter(
            request,
            new MockHttpServletResponse(),
            new MockFilterChain()
        );

        // then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
