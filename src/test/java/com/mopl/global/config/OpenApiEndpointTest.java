package com.mopl.global.config;

import com.mopl.sample.controller.SampleController;
import com.mopl.sample.service.SampleService;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springdoc.webmvc.ui.SwaggerConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SampleController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(OpenApiConfig.class)
@EnableConfigurationProperties({
        SpringDocConfigProperties.class,
        SwaggerUiConfigProperties.class,
        SwaggerUiOAuthProperties.class
})
@ImportAutoConfiguration({
        SpringDocConfiguration.class,
        SpringDocWebMvcConfiguration.class,
        SwaggerConfig.class
})
class OpenApiEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SampleService sampleService;

    @Test
    void OpenAPI_JSON을_조회할_수_있다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value("3.1.0"))
                .andExpect(jsonPath("$.info.title").value("모두의 플리 (Mopl) API"))
                .andExpect(jsonPath("$.components.securitySchemes.BearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.CsrfToken.name").value("X-XSRF-TOKEN"))
                .andExpect(jsonPath(
                        "$.paths['/api/samples'].post.security[0].BearerAuth"
                ).isArray())
                .andExpect(jsonPath(
                        "$.paths['/api/samples'].post.security[0].CsrfToken"
                ).isArray());
    }

    @Test
    void Swagger_UI_주소로_접근할_수_있다() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/swagger-ui/index.html"));
    }
}
