package com.mopl.global.outbox.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mopl.global.config.SecurityConfig;
import com.mopl.global.outbox.OutboxEvent;
import com.mopl.global.outbox.OutboxFailureService;
import com.mopl.global.outbox.OutboxRequeueOutcome;
import com.mopl.global.outbox.OutboxSkipOutcome;
import com.mopl.global.security.JwtProvider;
import com.mopl.user.service.AccessTokenUserStatusService;
import com.mopl.user.service.AccessTokenAuthenticationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 최종 실패 Outbox 운영 경계의 권한과 응답 계약을 검증합니다.
 *
 * <p>실제 {@code SecurityFilterChain} 을 함께 올립니다. 필터를 끄고 Controller 만 부르면
 * "관리자만 부를 수 있다"는 이 API 의 가장 중요한 성질이 검증되지 않습니다.
 */
@WebMvcTest(OutboxAdminController.class)
@ActiveProfiles("test")
@Import(SecurityConfig.class)
class OutboxAdminControllerTest {

    private static final String ADMIN_TOKEN = "admin-token";
    private static final String USER_TOKEN = "user-token";
    private static final UUID ACTOR_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID EVENT_ID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    private static final String FAILURES_PATH = "/api/admin/outbox/failures";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JwtProvider jwtProvider;

    @MockitoBean
    OutboxFailureService outboxFailureService;

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

    /** 최종 실패는 relay 가 만드는 상태입니다. 여기서는 상태 전이 메서드로 같은 모양을 만듭니다. */
    private OutboxEvent failedEvent(String lastError) {
        Instant occurredAt = Instant.parse("2026-08-15T03:00:00Z");
        OutboxEvent event = new OutboxEvent(
            EVENT_ID, "follow.created", 1, UUID.randomUUID(), occurredAt,
            "{\"followerId\":\"a\"}", "partition-key", "AGGREGATE",
            "follow.created:1", occurredAt);
        event.markAttemptFailed(lastError, occurredAt);
        event.markAttemptFailed(lastError, occurredAt);
        event.markFailed(lastError);
        return event;
    }

    @Test
    @DisplayName("인증 없이 목록을 조회하면 401을 반환한다")
    void findFailures_unauthenticated_returnsUnauthorized() throws Exception {
        mockMvc.perform(get(FAILURES_PATH))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("COMMON_401_1"));

        verifyNoInteractions(outboxFailureService);
    }

    /**
     * 인증은 통과했지만 권한이 없는 경우입니다. 403 이 인증 실패가 아니라 권한 부족에서 왔음을
     * JwtProvider 호출로 확인합니다.
     */
    @Test
    @DisplayName("일반 사용자가 목록을 조회하면 403을 반환한다")
    void findFailures_user_returnsForbidden() throws Exception {
        authenticate(USER_TOKEN, "USER");

        mockMvc.perform(get(FAILURES_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + USER_TOKEN))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("COMMON_403_1"));

        verify(jwtProvider).getAuthentication(USER_TOKEN);
        verifyNoInteractions(outboxFailureService);
    }

    /**
     * payload 에는 DM 본문처럼 도메인이 사용자에게만 보이기로 한 값이 들어갑니다. 운영 조회가
     * 그 경계를 우회하는 통로가 되면 안 됩니다.
     */
    @Test
    @DisplayName("관리자 목록 응답에 실패 원인은 담고 payload는 담지 않는다")
    void findFailures_admin_returnsFailuresWithoutPayload() throws Exception {
        authenticate(ADMIN_TOKEN, "ADMIN");
        when(outboxFailureService.findFailed(20)).thenReturn(List.of(failedEvent("발행 실패")));
        when(outboxFailureService.countFailed()).thenReturn(7L);

        mockMvc.perform(get(FAILURES_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(7))
            .andExpect(jsonPath("$.items[0].eventId").value(EVENT_ID.toString()))
            .andExpect(jsonPath("$.items[0].type").value("follow.created"))
            .andExpect(jsonPath("$.items[0].occurredAt").exists())
            .andExpect(jsonPath("$.items[0].attempts").value(3))
            .andExpect(jsonPath("$.items[0].lastError").value("발행 실패"))
            .andExpect(jsonPath("$.items[0].payload").doesNotExist())
            .andExpect(jsonPath("$.items[0].partitionKey").doesNotExist());
    }

    /**
     * {@code last_error} 는 길이 제한이 없는 컬럼이고 스택 트레이스가 통째로 들어갑니다.
     * 상한 없이 목록에 실으면 응답 하나가 조회 상한만큼 곱해져 커집니다.
     */
    @Test
    @DisplayName("긴 실패 원인은 잘라서 내려준다")
    void findFailures_truncatesLongLastError() throws Exception {
        authenticate(ADMIN_TOKEN, "ADMIN");
        String lastError = "가".repeat(600);
        when(outboxFailureService.findFailed(20)).thenReturn(List.of(failedEvent(lastError)));

        mockMvc.perform(get(FAILURES_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].lastError")
                .value("가".repeat(500) + "...(생략)"));
    }

    /**
     * 최종 실패는 사람이 개입할 때까지 지워지지 않습니다. 상한 없는 조회를 열어두면 한 번의
     * 요청이 밀린 전체를 실어 나릅니다.
     */
    @Test
    @DisplayName("조회 상한이 허용 범위를 벗어나면 400을 반환한다")
    void findFailures_limitOutOfRange_returnsBadRequest() throws Exception {
        authenticate(ADMIN_TOKEN, "ADMIN");

        mockMvc.perform(get(FAILURES_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN)
                .param("limit", "101"))
            .andExpect(status().isBadRequest());

        verify(outboxFailureService, never()).findFailed(anyInt());
    }

    @Test
    @DisplayName("관리자가 재처리하면 204를 반환한다")
    void requeue_admin_returnsNoContent() throws Exception {
        authenticate(ADMIN_TOKEN, "ADMIN");
        when(outboxFailureService.requeue(any(), any()))
            .thenReturn(OutboxRequeueOutcome.REQUEUED);

        mockMvc.perform(post(FAILURES_PATH + "/" + EVENT_ID + "/requeue")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN)
                .with(csrf()))
            .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("없는 eventId를 재처리하면 404를 반환한다")
    void requeue_unknownEvent_returnsNotFound() throws Exception {
        authenticate(ADMIN_TOKEN, "ADMIN");
        when(outboxFailureService.requeue(any(), any()))
            .thenReturn(OutboxRequeueOutcome.NOT_FOUND);

        mockMvc.perform(post(FAILURES_PATH + "/" + EVENT_ID + "/requeue")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN)
                .with(csrf()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("OUTBOX_404_1"))
            .andExpect(jsonPath("$.details.eventId").value(EVENT_ID.toString()));
    }

    /**
     * 같은 요청이 두 번 들어오면 두 번째는 이 경로로 들어옵니다. 첫 요청이 이미 발행 대기로
     * 돌려놨으므로 최종 실패가 아닙니다. 없는 이벤트와 구분되어야 운영자가 무엇이 일어났는지
     * 알 수 있습니다.
     */
    @Test
    @DisplayName("최종 실패 상태가 아닌 이벤트를 재처리하면 409를 반환한다")
    void requeue_notFailedEvent_returnsConflict() throws Exception {
        authenticate(ADMIN_TOKEN, "ADMIN");
        when(outboxFailureService.requeue(any(), any()))
            .thenReturn(OutboxRequeueOutcome.NOT_FAILED);

        mockMvc.perform(post(FAILURES_PATH + "/" + EVENT_ID + "/requeue")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN)
                .with(csrf()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("OUTBOX_409_1"));
    }

    @Test
    @DisplayName("일반 사용자가 재처리하면 403을 반환하고 상태를 바꾸지 않는다")
    void requeue_user_returnsForbidden() throws Exception {
        authenticate(USER_TOKEN, "USER");

        mockMvc.perform(post(FAILURES_PATH + "/" + EVENT_ID + "/requeue")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + USER_TOKEN)
                .with(csrf()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("COMMON_403_1"));

        verifyNoInteractions(outboxFailureService);
    }

    @Test
    @DisplayName("관리자가 건너뛰면 204를 반환한다")
    void skip_admin_returnsNoContent() throws Exception {
        authenticate(ADMIN_TOKEN, "ADMIN");
        when(outboxFailureService.skip(any(), any(), any(), any()))
            .thenReturn(OutboxSkipOutcome.SKIPPED);

        mockMvc.perform(post(FAILURES_PATH + "/" + EVENT_ID + "/skip")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"업무 영향 확인함\"}")
                .with(csrf()))
            .andExpect(status().isNoContent());

        verify(outboxFailureService).skip(eq(EVENT_ID), eq(ACTOR_ID), eq("업무 영향 확인함"), any());
    }

    /**
     * 응답을 못 본 운영자가 같은 요청을 다시 보내는 상황입니다. 결과는 "그 이벤트는 건너뛴
     * 상태다"로 같으므로 거절하지 않습니다.
     */
    @Test
    @DisplayName("이미 건너뛴 이벤트를 다시 건너뛰어도 204를 반환한다")
    void skip_alreadySkipped_returnsNoContent() throws Exception {
        authenticate(ADMIN_TOKEN, "ADMIN");
        when(outboxFailureService.skip(any(), any(), any(), any()))
            .thenReturn(OutboxSkipOutcome.ALREADY_SKIPPED);

        mockMvc.perform(post(FAILURES_PATH + "/" + EVENT_ID + "/skip")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"업무 영향 확인함\"}")
                .with(csrf()))
            .andExpect(status().isNoContent());
    }

    /**
     * 사유는 이 전환의 내용 그 자체입니다. 비어 있으면 나중에 그 행을 보고 무슨 일이 있었는지
     * 알 수 없어 단순히 지운 것과 다르지 않습니다.
     */
    @Test
    @DisplayName("사유가 비어 있으면 400을 반환하고 상태를 바꾸지 않는다")
    void skip_blankReason_returnsBadRequest() throws Exception {
        authenticate(ADMIN_TOKEN, "ADMIN");

        mockMvc.perform(post(FAILURES_PATH + "/" + EVENT_ID + "/skip")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"   \"}")
                .with(csrf()))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(outboxFailureService);
    }

    @Test
    @DisplayName("최종 실패 상태가 아닌 이벤트를 건너뛰면 409를 반환한다")
    void skip_notFailedEvent_returnsConflict() throws Exception {
        authenticate(ADMIN_TOKEN, "ADMIN");
        when(outboxFailureService.skip(any(), any(), any(), any()))
            .thenReturn(OutboxSkipOutcome.NOT_FAILED);

        mockMvc.perform(post(FAILURES_PATH + "/" + EVENT_ID + "/skip")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"업무 영향 확인함\"}")
                .with(csrf()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("OUTBOX_409_1"));
    }

    @Test
    @DisplayName("없는 eventId를 건너뛰면 404를 반환한다")
    void skip_unknownEvent_returnsNotFound() throws Exception {
        authenticate(ADMIN_TOKEN, "ADMIN");
        when(outboxFailureService.skip(any(), any(), any(), any()))
            .thenReturn(OutboxSkipOutcome.NOT_FOUND);

        mockMvc.perform(post(FAILURES_PATH + "/" + EVENT_ID + "/skip")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"업무 영향 확인함\"}")
                .with(csrf()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("OUTBOX_404_1"));
    }

    @Test
    @DisplayName("일반 사용자가 건너뛰면 403을 반환하고 상태를 바꾸지 않는다")
    void skip_user_returnsForbidden() throws Exception {
        authenticate(USER_TOKEN, "USER");

        mockMvc.perform(post(FAILURES_PATH + "/" + EVENT_ID + "/skip")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + USER_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"업무 영향 확인함\"}")
                .with(csrf()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("COMMON_403_1"));

        verifyNoInteractions(outboxFailureService);
    }

    @Test
    @DisplayName("CSRF 토큰이 없는 재처리 요청은 403을 반환한다")
    void requeue_withoutCsrf_returnsForbidden() throws Exception {
        authenticate(ADMIN_TOKEN, "ADMIN");

        mockMvc.perform(post(FAILURES_PATH + "/" + EVENT_ID + "/requeue")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("COMMON_403_1"));

        verifyNoInteractions(outboxFailureService);
    }
}
