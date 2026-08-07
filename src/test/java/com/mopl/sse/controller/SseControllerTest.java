package com.mopl.sse.controller;

import org.junit.jupiter.api.DisplayName;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mopl.sse.service.SseEmitterManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@WebMvcTest(SseController.class)
@AutoConfigureMockMvc(addFilters = false)
public class SseControllerTest {

    private static final UUID USER_ID =
        UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    SseEmitterManager sseEmitterManager;

    @Test
    @DisplayName("인증 사용자의 ID로 SSE 연결을 생성")
    void subscribe_authenticatedUser_returnsEmitter() throws Exception {
        // given
        SseEmitter emitter = new SseEmitter();

        when(
            sseEmitterManager.subscribe(USER_ID)
        ).thenReturn(emitter);

        // when & then
        mockMvc.perform(
            get("/api/sse")
                .principal(
                    () -> USER_ID.toString()
                )
            )
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted());

        verify(sseEmitterManager).subscribe(
            USER_ID
        );

        emitter.complete();
    }

    @Test
    @DisplayName("인증 정보 없이 SSE 연결을 요청하면 401을 반환")
    void subscribe_unauthenticated_returnsUnauthorized()
        throws Exception {

        // when & then
        mockMvc.perform(
                get("/api/sse")
            )
            .andExpect(status().isUnauthorized())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_401_1")
            );

        verifyNoInteractions(
            sseEmitterManager
        );
    }

    @Test
    @DisplayName("인증 사용자 ID가 UUID 형식이 아니면 401을 반환")
    void subscribe_invalidPrincipal_returnsUnauthorized()
        throws Exception {

        // when & then
        mockMvc.perform(
                get("/api/sse")
                    .principal(
                        () -> "invalid-user-id"
                    )
            )
            .andExpect(status().isUnauthorized())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_401_1")
            );

        verifyNoInteractions(
            sseEmitterManager
        );
    }
}
