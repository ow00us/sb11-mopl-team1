package com.mopl.content.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mopl.content.external.mapping.TmdbContentLocalizationBackfillService;
import com.mopl.content.external.mapping.TmdbContentLocalizationBackfillService.BackfillResult;
import com.mopl.global.config.SecurityConfig;
import com.mopl.global.security.JwtProvider;
import com.mopl.user.service.AccessTokenUserStatusService;
import com.mopl.user.service.AccessTokenAuthenticationStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * TMDB 콘텐츠 현지화 백필 트리거의 권한과 응답 계약을 검증합니다.
 *
 * <p>실제 {@code SecurityFilterChain} 을 함께 올립니다. 필터를 끄고 Controller 만 부르면
 * "관리자만 부를 수 있다"는 이 API 의 가장 중요한 성질이 검증되지 않습니다.
 */
@WebMvcTest(TmdbContentLocalizationAdminController.class)
@ActiveProfiles("test")
@Import(SecurityConfig.class)
class TmdbContentLocalizationAdminControllerTest {

    private static final String ADMIN_TOKEN = "admin-token";
    private static final String USER_TOKEN = "user-token";
    private static final UUID ACTOR_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String BACKFILL_PATH = "/api/admin/contents/tmdb-localization/backfill";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JwtProvider jwtProvider;

    @MockitoBean
    TmdbContentLocalizationBackfillService backfillService;

    @MockitoBean
    AccessTokenUserStatusService accessTokenUserStatusService;

    private void authenticate(String token, String role) {
        when(jwtProvider.validate(token)).thenReturn(true);
        when(jwtProvider.getAuthentication(token)).thenReturn(
            UsernamePasswordAuthenticationToken.authenticated(
                ACTOR_ID, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        when(accessTokenUserStatusService.resolve(
                ACTOR_ID)).thenReturn(AccessTokenAuthenticationStatus.ALLOWED);
    }

    @Test
    @DisplayName("인증 없이 백필을 호출하면 401을 반환한다")
    void backfill_unauthenticated_returnsUnauthorized() throws Exception {
        mockMvc.perform(post(BACKFILL_PATH).with(csrf()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("COMMON_401_1"));

        verifyNoInteractions(backfillService);
    }

    @Test
    @DisplayName("일반 사용자가 백필을 호출하면 403을 반환한다")
    void backfill_user_returnsForbidden() throws Exception {
        authenticate(USER_TOKEN, "USER");

        mockMvc.perform(post(BACKFILL_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + USER_TOKEN)
                .with(csrf()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("COMMON_403_1"));

        verifyNoInteractions(backfillService);
    }

    @Test
    @DisplayName("관리자가 백필을 호출하면 200과 함께 처리 결과 건수를 그대로 반환한다")
    void backfill_admin_returnsOkWithResult() throws Exception {
        authenticate(ADMIN_TOKEN, "ADMIN");
        when(backfillService.backfill()).thenReturn(new BackfillResult(10, 7, 3));

        mockMvc.perform(post(BACKFILL_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN)
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(10))
            .andExpect(jsonPath("$.updated").value(7))
            .andExpect(jsonPath("$.failed").value(3));
    }

    @Test
    @DisplayName("CSRF 토큰이 없는 백필 요청은 403을 반환한다")
    void backfill_withoutCsrf_returnsForbidden() throws Exception {
        authenticate(ADMIN_TOKEN, "ADMIN");

        mockMvc.perform(post(BACKFILL_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("COMMON_403_1"));

        verifyNoInteractions(backfillService);
    }
}
