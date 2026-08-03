package com.mopl.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.content.controller.ContentController;
import com.mopl.content.service.ContentService;
import com.mopl.directmessage.controller.ConversationController;
import com.mopl.directmessage.controller.DirectMessageController;
import com.mopl.directmessage.service.ConversationService;
import com.mopl.directmessage.service.DirectMessageService;
import com.mopl.follow.controller.FollowController;
import com.mopl.follow.service.FollowService;
import com.mopl.global.security.controller.CsrfTokenController;
import com.mopl.notification.controller.NotificationController;
import com.mopl.notification.service.NotificationService;
import com.mopl.playlist.controller.PlaylistController;
import com.mopl.playlist.service.PlaylistService;
import com.mopl.user.controller.AuthController;
import com.mopl.user.controller.UserController;
import com.mopl.user.service.AuthService;
import com.mopl.user.service.UserService;
import com.mopl.watchingsession.controller.WatchingSessionController;
import com.mopl.watchingsession.service.WatchingSessionService;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
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
import org.yaml.snakeyaml.Yaml;

/** 정적 합의 계약과 Spring MVC가 생성한 런타임 OpenAPI의 핵심 HTTP 계약을 대조합니다. */
@WebMvcTest(controllers = {
    AuthController.class,
    ContentController.class,
    ConversationController.class,
    CsrfTokenController.class,
    DirectMessageController.class,
    FollowController.class,
    NotificationController.class,
    PlaylistController.class,
    UserController.class,
    WatchingSessionController.class
})
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
class OpenApiRuntimeContractTest {

    private static final Path CONTRACT_PATH = Path.of("openapi/mopl-api.yaml");
    private static final Set<String> HTTP_METHODS = Set.of(
        "get", "post", "put", "patch", "delete"
    );
    private static Map<String, Object> contract;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    ContentService contentService;

    @MockitoBean
    ConversationService conversationService;

    @MockitoBean
    DirectMessageService directMessageService;

    @MockitoBean
    FollowService followService;

    @MockitoBean
    NotificationService notificationService;

    @MockitoBean
    PlaylistService playlistService;

    @MockitoBean
    AuthService authService;

    @MockitoBean
    UserService userService;

    @MockitoBean
    WatchingSessionService watchingSessionService;

    @BeforeAll
    static void loadContract() throws Exception {
        try (InputStream input = Files.newInputStream(CONTRACT_PATH)) {
            contract = new Yaml().load(input);
        }
    }

    @Test
    void exposesOpenApi31DocumentWithCommonSecuritySchemes() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.openapi").value("3.1.0"))
            .andExpect(jsonPath("$.info.title").value("모두의 플리 (Mopl) API"))
            .andExpect(jsonPath("$.components.securitySchemes.BearerAuth.type")
                .value("http"))
            .andExpect(jsonPath("$.components.securitySchemes.CsrfToken.name")
                .value("X-XSRF-TOKEN"));
    }

    @Test
    void exposesSwaggerUiEntryPoint() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", "/swagger-ui/index.html"));
    }

    @Test
    void implementedHttpOperationsMatchTheAgreedContract() throws Exception {
        String runtimeJson = mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        Map<String, Object> runtime = objectMapper.readValue(
            runtimeJson,
            new TypeReference<>() {
            }
        );

        List<String> differences = compareImplementedOperations(runtime, contract);

        assertThat(differences)
            .withFailMessage(() -> "OpenAPI 계약 불일치:\n- " + String.join("\n- ", differences))
            .isEmpty();
    }

    private static List<String> compareImplementedOperations(
        Map<String, Object> runtime,
        Map<String, Object> agreed
    ) {
        List<String> differences = new ArrayList<>();
        Map<String, Object> runtimePaths = map(runtime, "paths");
        Map<String, Object> agreedPaths = map(agreed, "paths");

        runtimePaths.forEach((path, runtimePathValue) -> {
            if (!path.startsWith("/api/")) {
                return;
            }

            Map<String, Object> runtimePath = asMap(runtimePathValue);
            Map<String, Object> agreedPath = nullableMap(agreedPaths.get(path));
            if (agreedPath == null) {
                differences.add(path + " 경로가 합의 계약에 없음");
                return;
            }

            HTTP_METHODS.stream()
                .filter(runtimePath::containsKey)
                .forEach(method -> compareOperation(
                    path,
                    method,
                    asMap(runtimePath.get(method)),
                    agreedPath,
                    runtime,
                    agreed,
                    differences
                ));
        });

        return differences;
    }

    private static void compareOperation(
        String path,
        String method,
        Map<String, Object> runtimeOperation,
        Map<String, Object> agreedPath,
        Map<String, Object> runtimeDocument,
        Map<String, Object> agreedDocument,
        List<String> differences
    ) {
        String operationName = method.toUpperCase() + " " + path;
        Map<String, Object> agreedOperation = nullableMap(agreedPath.get(method));
        if (agreedOperation == null) {
            differences.add(operationName + " 메서드가 합의 계약에 없음");
            return;
        }

        compareValue(
            operationName,
            "요청 Content-Type",
            requestContentTypes(runtimeOperation),
            requestContentTypes(agreedOperation),
            differences
        );
        compareValue(
            operationName,
            "성공 상태 코드",
            successResponseCodes(runtimeOperation),
            successResponseCodes(agreedOperation),
            differences
        );
        compareValue(
            operationName,
            "보안 요구",
            effectiveSecurity(runtimeDocument, runtimeOperation),
            effectiveSecurity(agreedDocument, agreedOperation),
            differences
        );
    }

    private static void compareValue(
        String operation,
        String field,
        Object runtime,
        Object agreed,
        List<String> differences
    ) {
        if (!runtime.equals(agreed)) {
            differences.add(operation + " " + field
                + " 런타임=" + runtime + ", 계약=" + agreed);
        }
    }

    private static Set<String> requestContentTypes(Map<String, Object> operation) {
        Map<String, Object> requestBody = nullableMap(operation.get("requestBody"));
        if (requestBody == null) {
            return Set.of();
        }
        Map<String, Object> content = nullableMap(requestBody.get("content"));
        return content == null ? Set.of() : new TreeSet<>(content.keySet());
    }

    private static Set<String> successResponseCodes(Map<String, Object> operation) {
        Map<String, Object> responses = map(operation, "responses");
        Set<String> result = new TreeSet<>();
        responses.keySet().stream()
            .filter(code -> code.matches("2\\d\\d"))
            .forEach(result::add);
        return result;
    }

    private static Set<String> effectiveSecurity(
        Map<String, Object> document,
        Map<String, Object> operation
    ) {
        Object value = operation.containsKey("security")
            ? operation.get("security")
            : document.get("security");
        if (!(value instanceof Collection<?> requirements)) {
            return Set.of();
        }

        Set<String> normalized = new LinkedHashSet<>();
        for (Object requirementValue : requirements) {
            Map<String, Object> requirement = asMap(requirementValue);
            normalized.add(String.join("+", new TreeSet<>(requirement.keySet())));
        }
        return normalized;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nullableMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    private static Map<String, Object> map(Map<String, Object> source, String key) {
        Map<String, Object> value = nullableMap(source.get(key));
        if (value == null) {
            throw new IllegalStateException("OpenAPI 문서에 " + key + " 객체가 없습니다.");
        }
        return value;
    }
}
