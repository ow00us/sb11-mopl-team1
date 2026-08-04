package com.mopl.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.MoplApplication;
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
import com.mopl.review.controller.ReviewController;
import com.mopl.review.service.ReviewService;
import com.mopl.sample.controller.SampleController;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
import org.springframework.web.bind.annotation.RestController;
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
    ReviewController.class,
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
    private static final Set<String> EXPECTED_IMPLEMENTED_OPERATIONS = Set.of(
        "DELETE /api/contents/{contentId}",
        "DELETE /api/follows/{followId}",
        "DELETE /api/notifications/{notificationId}",
        "DELETE /api/playlists/{playlistId}",
        "DELETE /api/playlists/{playlistId}/contents/{contentId}",
        "DELETE /api/playlists/{playlistId}/subscription",
        "DELETE /api/reviews/{reviewId}",
        "GET /api/auth/csrf-token",
        "GET /api/contents",
        "GET /api/contents/{contentId}",
        "GET /api/contents/{contentId}/watching-sessions",
        "GET /api/conversations",
        "GET /api/conversations/with",
        "GET /api/conversations/{conversationId}",
        "GET /api/conversations/{conversationId}/direct-messages",
        "GET /api/follows/count",
        "GET /api/follows/followed-by-me",
        "GET /api/follows/followers",
        "GET /api/follows/followings",
        "GET /api/notifications",
        "GET /api/playlists",
        "GET /api/playlists/{playlistId}",
        "GET /api/playlists/{playlistId}/subscribers",
        "GET /api/reviews",
        "GET /api/users/{userId}",
        "GET /api/users/{watcherId}/watching-sessions",
        "PATCH /api/contents/{contentId}",
        "PATCH /api/playlists/{playlistId}",
        "PATCH /api/reviews/{reviewId}",
        "PATCH /api/users/{userId}",
        "PATCH /api/users/{userId}/password",
        "POST /api/auth/sign-in",
        "POST /api/contents",
        "POST /api/conversations",
        "POST /api/conversations/{conversationId}/direct-messages/{directMessageId}/read",
        "POST /api/follows",
        "POST /api/playlists",
        "POST /api/playlists/{playlistId}/contents/{contentId}",
        "POST /api/playlists/{playlistId}/subscription",
        "POST /api/reviews",
        "POST /api/users"
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
    ReviewService reviewService;

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
    void commonDocumentMetadataMatchesTheAgreedContract() throws Exception {
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

        List<String> differences = compareCommonDocument(runtime, contract);

        assertThat(differences)
            .withFailMessage(() -> "OpenAPI 공통 정보 불일치:\n- "
                + String.join("\n- ", differences))
            .isEmpty();
    }

    @Test
    void exposesSwaggerUiEntryPoint() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", "/swagger-ui/index.html"));
    }

    @Test
    void includesEveryProductionRestController() throws Exception {
        Set<Class<?>> configuredControllers = Set.of(
            OpenApiRuntimeContractTest.class.getAnnotation(WebMvcTest.class).controllers()
        );

        assertThat(configuredControllers)
            .as("새 운영 REST Controller는 계약 검증 대상에 등록되어야 합니다.")
            .containsExactlyInAnyOrderElementsOf(productionRestControllers());
    }

    @Test
    void rejectsContentInAgreedNoContentResponse() {
        Map<String, Object> agreedOperation = Map.of(
            "responses",
            Map.of("204", Map.of("content", Map.of()))
        );
        List<String> differences = new ArrayList<>();

        validateNoContentResponse(
            "GET /api/example",
            "정적 계약",
            agreedOperation,
            differences
        );

        assertThat(differences).containsExactly(
            "GET /api/example 정적 계약 204 응답에 본문 content가 문서화됨"
        );
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

        compareOperationInventory(httpOperations(runtimePaths), differences);

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

    private static List<String> compareCommonDocument(
        Map<String, Object> runtime,
        Map<String, Object> agreed
    ) {
        List<String> differences = new ArrayList<>();
        compareValue("OpenAPI 문서", "버전", runtime.get("openapi"),
            agreed.get("openapi"), differences);
        compareValue("OpenAPI 문서", "공통 정보", map(runtime, "info"),
            map(agreed, "info"), differences);
        compareValue("OpenAPI 문서", "서버", runtime.get("servers"),
            agreed.get("servers"), differences);
        compareValue("OpenAPI 문서", "보안 스키마", securitySchemes(runtime),
            securitySchemes(agreed), differences);
        compareValue("OpenAPI 문서", "전역 보안 요구", securityRequirements(runtime.get("security")),
            securityRequirements(agreed.get("security")), differences);
        return differences;
    }

    private static Set<String> httpOperations(Map<String, Object> paths) {
        Set<String> operations = new TreeSet<>();
        paths.forEach((path, pathValue) -> {
            if (!path.startsWith("/api/")) {
                return;
            }
            Map<String, Object> pathItem = asMap(pathValue);
            HTTP_METHODS.stream()
                .filter(pathItem::containsKey)
                .map(method -> method.toUpperCase() + " " + path)
                .forEach(operations::add);
        });
        return operations;
    }

    private static void compareOperationInventory(
        Set<String> runtimeOperations,
        List<String> differences
    ) {
        Set<String> missing = new TreeSet<>(EXPECTED_IMPLEMENTED_OPERATIONS);
        missing.removeAll(runtimeOperations);
        missing.forEach(operation ->
            differences.add(operation + " 구현 대상 operation이 런타임 문서에서 사라짐"));

        Set<String> unexpected = new TreeSet<>(runtimeOperations);
        unexpected.removeAll(EXPECTED_IMPLEMENTED_OPERATIONS);
        unexpected.forEach(operation ->
            differences.add(operation + " 구현 대상 operation 목록에 등록되지 않음"));
    }

    private static Map<String, Object> securitySchemes(Map<String, Object> document) {
        Map<String, Object> schemes = map(map(document, "components"), "securitySchemes");
        return Map.of(
            OpenApiConfig.BEARER_AUTH,
            selectedValues(map(schemes, OpenApiConfig.BEARER_AUTH),
                "type", "scheme", "bearerFormat"),
            OpenApiConfig.CSRF_TOKEN,
            selectedValues(map(schemes, OpenApiConfig.CSRF_TOKEN),
                "type", "in", "name")
        );
    }

    private static Map<String, Object> selectedValues(
        Map<String, Object> source,
        String... keys
    ) {
        Map<String, Object> selected = new LinkedHashMap<>();
        Stream.of(keys).forEach(key -> selected.put(key, source.get(key)));
        return selected;
    }

    private static Set<Class<?>> productionRestControllers() throws Exception {
        Path classesRoot = Path.of(MoplApplication.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI());
        Path packageRoot = classesRoot.resolve(Path.of("com", "mopl"));

        try (Stream<Path> classFiles = Files.walk(packageRoot)) {
            return classFiles
                .filter(path -> path.toString().endsWith(".class"))
                .filter(path -> !path.getFileName().toString().contains("$"))
                .map(path -> loadClass(classesRoot, path))
                .filter(type -> type.isAnnotationPresent(RestController.class))
                .filter(type -> !type.equals(SampleController.class))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    private static Class<?> loadClass(Path classesRoot, Path classFile) {
        String className = classesRoot.relativize(classFile).toString()
            .replace(classFile.getFileSystem().getSeparator(), ".")
            .replaceFirst("\\.class$", "");
        try {
            return Class.forName(className, false,
                OpenApiRuntimeContractTest.class.getClassLoader());
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Controller class를 읽을 수 없습니다: " + className,
                exception);
        }
    }

    private static Set<String> securityRequirements(Object value) {
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
        validateNoContentResponse(
            operationName,
            "런타임",
            runtimeOperation,
            differences
        );
        validateNoContentResponse(
            operationName,
            "정적 계약",
            agreedOperation,
            differences
        );
    }

    private static void validateNoContentResponse(
        String operationName,
        String source,
        Map<String, Object> operation,
        List<String> differences
    ) {
        Map<String, Object> noContent = nullableMap(
            map(operation, "responses").get("204")
        );
        if (noContent != null && noContent.containsKey("content")) {
            differences.add(operationName + " " + source
                + " 204 응답에 본문 content가 문서화됨");
        }
    }

    private static void compareValue(
        String operation,
        String field,
        Object runtime,
        Object agreed,
        List<String> differences
    ) {
        if (!Objects.equals(runtime, agreed)) {
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
        return securityRequirements(value);
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
