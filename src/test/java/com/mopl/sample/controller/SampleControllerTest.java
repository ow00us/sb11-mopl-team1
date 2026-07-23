package com.mopl.sample.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.sample.dto.SampleCreateRequest;
import com.mopl.sample.dto.SampleDto;
import com.mopl.sample.service.SampleService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SampleController.class)
@AutoConfigureMockMvc(addFilters = false)
class SampleControllerTest {

    private static final UUID SAMPLE_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant CREATED_AT = Instant.parse("2026-07-23T03:00:00Z");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    SampleService sampleService;

    @Test
    @DisplayName("샘플 생성 성공 시 201과 생성된 샘플을 반환")
    void create_success() throws Exception {
        // given
        Map<String, String> request = Map.of("name", "sample");
        SampleDto response = new SampleDto(SAMPLE_ID, "sample", CREATED_AT);

        when(sampleService.create(any())).thenReturn(response);

        // when & then
        mockMvc.perform(post("/api/samples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(SAMPLE_ID.toString()))
                .andExpect(jsonPath("$.name").value("sample"))
                .andExpect(jsonPath("$.createdAt").value(CREATED_AT.toString()));

        verify(sampleService).create(new SampleCreateRequest("sample"));
    }

    @Test
    @DisplayName("이름이 비어 있으면 400과 검증 오류를 반환")
    void create_fail_whenNameBlank() throws Exception {
        // given
        Map<String, String> request = Map.of("name", "");

        // when & then
        mockMvc.perform(post("/api/samples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errorCode").value("COMMON_400_1"))
                .andExpect(jsonPath("$.details.name").exists());

        verifyNoInteractions(sampleService);
    }

    @Test
    @DisplayName("조회 대상 샘플이 없으면 404를 반환")
    void get_fail_whenSampleNotFound() throws Exception {
        // given
        when(sampleService.get(SAMPLE_ID))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        // when & then
        mockMvc.perform(get("/api/samples/{id}", SAMPLE_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errorCode").value("COMMON_404_1"));
    }
}
