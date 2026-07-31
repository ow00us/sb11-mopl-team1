package com.mopl.global.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ExceptionHandlerTestController.class)
@AutoConfigureMockMvc(addFilters = false)
public class GlobalExceptionHandlerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("limit 값의 타입이 불일치하면 400")
    void limit_typeMismatch_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/test/exception-handler/params")
                .param("limit", "abc")
                .param("sortBy", "createdAt")
                .param("sortDirection", "DESCENDING"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("limit 파라미터 누락 시 400")
    void limit_missing_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/test/exception-handler/params")
                .param("sortBy", "createdAt")
                .param("sortDirection", "DESCENDING"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("sortBy 파라미터 누락 시 400")
    void sortBy_missing_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/test/exception-handler/params")
                .param("limit", "10")
                .param("sortDirection", "DESCENDING"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("sortDirection 파라미터 누락 시 400")
    void sortDirection_missing_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/test/exception-handler/params")
                .param("sortBy", "createdAt")
                .param("limit", "10"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("정상 요청은 200 반환")
    void normal_request_returnsOk() throws Exception {
        mockMvc.perform(get("/test/exception-handler/params")
                .param("limit", "10")
                .param("sortBy", "createdAt")
                .param("sortDirection", "DESCENDING")
                .principal(() -> UUID.randomUUID().toString()))
            .andExpect(status().isOk());
    }

}
