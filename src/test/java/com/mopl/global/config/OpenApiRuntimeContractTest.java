package com.mopl.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

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
import com.mopl.global.outbox.OutboxFailureService;
import com.mopl.global.outbox.controller.OutboxAdminController;
import com.mopl.global.security.controller.CsrfTokenController;
import com.mopl.notification.controller.NotificationController;
import com.mopl.notification.service.NotificationService;
import com.mopl.playlist.controller.PlaylistController;
import com.mopl.playlist.service.PlaylistService;
import com.mopl.review.controller.ReviewController;
import com.mopl.review.service.ReviewService;
import com.mopl.sample.controller.SampleController;
import com.mopl.sse.controller.SseController;
import com.mopl.sse.service.SseEmitterManager;
import com.mopl.user.cookie.RefreshTokenCookieFactory;
import com.mopl.user.controller.AuthController;
import com.mopl.user.controller.UserController;
import com.mopl.user.service.AuthService;
import com.mopl.user.service.PasswordResetService;
import com.mopl.user.service.UserService;
import com.mopl.user.service.RefreshTokenService;
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
    OutboxAdminController.class,
    SseController.class,
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
        "GET /api/admin/outbox/failures",
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
        "GET /api/follows/recommendations",
        "GET /api/notifications",
        "GET /api/sse",
        "GET /api/playlists",
        "GET /api/playlists/popular",
        "GET /api/playlists/{playlistId}",
        "GET /api/playlists/{playlistId}/subscribers",
        "GET /api/reviews",
        "GET /api/reviews/me",
        "GET /api/users",
        "GET /api/users/{userId}",
        "GET /api/users/{watcherId}/watching-sessions",
        "PATCH /api/contents/{contentId}",
        "PATCH /api/playlists/{playlistId}",
        "PATCH /api/reviews/{reviewId}",
        "PATCH /api/users/{userId}",
        "PATCH /api/users/{userId}/locked",
        "PATCH /api/users/{userId}/password",
        "PATCH /api/users/{userId}/role",
        "POST /api/admin/outbox/failures/{eventId}/requeue",
        "POST /api/auth/sign-in",
        "POST /api/auth/reset-password",
        "POST /api/auth/refresh",
        "POST /api/auth/sign-out",
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
    OutboxFailureService outboxFailureService;

    @MockitoBean
    PlaylistService playlistService;

    @MockitoBean
    ReviewService reviewService;

    @MockitoBean
    AuthService authService;

    @MockitoBean
    PasswordResetService passwordResetService;

    @MockitoBean
    RefreshTokenService refreshTokenService;

    @MockitoBean
    RefreshTokenCookieFactory refreshTokenCookieFactory;

    @MockitoBean
    UserService userService;

    @MockitoBean
    WatchingSessionService watchingSessionService;

    @MockitoBean
    SseEmitterManager sseEmitterManager;

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

    /**
     * 로그인 성공 응답에 Refresh Token Set-Cookie 계약이
     * 런타임 OpenAPI 문서에도 포함되는지 검증
     */
    @Test
    void documentsRefreshTokenCookieHeaderOnSignInSuccess()
        throws Exception {

        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(
                jsonPath(
                    "$.paths['/api/auth/sign-in']"
                        + ".post.responses['200']"
                        + ".headers['Set-Cookie']"
                        + ".schema.type"
                ).value("string")
            );
    }

    /**
     * 토큰 재발급 성공 응답에도 교체된 Refresh Token을 전달하는
     * Set-Cookie 헤더가 런타임 OpenAPI 문서에 포함되는지 검증
     */
    @Test
    void documentsRefreshTokenCookieHeaderOnRefreshSuccess()
        throws Exception {

        mockMvc.perform(
                get("/v3/api-docs")
            )
            .andExpect(status().isOk())
            .andExpect(
                jsonPath(
                    "$.paths['/api/auth/refresh']"
                        + ".post.responses['200']"
                        + ".headers['Set-Cookie']"
                        + ".schema.type"
                ).value("string")
            );
    }

    /**
     * 로그아웃 요청에서 현재 Refresh Token을 선택적으로 Cookie로 전달하고,
     * 성공 응답에서 해당 Cookie를 삭제하는 Set-Cookie 헤더가
     * 런타임 OpenAPI 문서에 포함되는지 검증
     */
    @Test
    void documentsRefreshTokenCookieContractOnSignOut()
        throws Exception {

        mockMvc.perform(
                get("/v3/api-docs")
            )
            .andExpect(status().isOk())

            /*
             * 로그아웃할 현재 브라우저의 Refresh Token을
             * Cookie에서 전달받는 계약인지 확인
             */
            .andExpect(
                jsonPath(
                    "$.paths['/api/auth/sign-out']"
                        + ".post.parameters[0].name"
                ).value("REFRESH_TOKEN")
            )
            .andExpect(
                jsonPath(
                    "$.paths['/api/auth/sign-out']"
                        + ".post.parameters[0].in"
                ).value("cookie")
            )
            .andExpect(
                jsonPath(
                    "$.paths['/api/auth/sign-out']"
                        + ".post.parameters[0].schema.type"
                ).value("string")
            )

            /*
             * 로그아웃 성공 시 브라우저의 Refresh Token Cookie를
             * 제거하는 Set-Cookie 헤더가 문서화됐는지 확인
             */
            .andExpect(
                jsonPath(
                    "$.paths['/api/auth/sign-out']"
                        + ".post.responses['204']"
                        + ".headers['Set-Cookie']"
                        + ".schema.type"
                ).value("string")
            );
    }

    /**
     * 비밀번호 초기화 API의 요청 DTO, 성공 응답과 보안 요구가
     * 런타임 OpenAPI 문서에 올바르게 노출되는지 검증
     *
     * <p>비밀번호 초기화는 로그인하지 않은 사용자도 호출할 수 있어야 하므로
     * BearerAuth는 요구하지 않고, 비밀번호 상태를 변경하는 POST 요청이므로
     * CsrfToken만 요구합니다.</p>
     */
    @Test
    void documentsPasswordResetContract()
        throws Exception {

        mockMvc.perform(
                get("/v3/api-docs")
            )
            .andExpect(
                status().isOk()
            )

            /*
             * 비밀번호 초기화 요청 본문은 JSON이고,
             * ResetPasswordRequest 스키마를 사용해야 한다.
             */
            .andExpect(
                jsonPath(
                    "$.paths['/api/auth/reset-password']"
                        + ".post.requestBody"
                        + ".content['application/json']"
                        + ".schema['$ref']"
                ).value(
                    "#/components/schemas/ResetPasswordRequest"
                )
            )

            /*
             * 기존 OpenAPI 계약대로 성공 상태는
             * 204 No Content
             */
            .andExpect(
                jsonPath(
                    "$.paths['/api/auth/reset-password']"
                        + ".post.responses['204']"
                ).exists()
            )

            /*
             * 204 응답에 content가 생성되면 응답 본문이 있는 것으로
             * 오해할 수 있으므로 content 속성이 없어야 한다.
             */
            .andExpect(
                jsonPath(
                    "$.paths['/api/auth/reset-password']"
                        + ".post.responses['204'].content"
                ).doesNotExist()
            )

            /*
             * 공개 API이므로 JWT Bearer 인증은 요구하지 않는다.
             */
            .andExpect(
                jsonPath(
                    "$.paths['/api/auth/reset-password']"
                        + ".post.security[0].BearerAuth"
                ).doesNotExist()
            )

            /*
             * 비밀번호를 변경하는 POST 요청이므로
             * CSRF 토큰은 필수 보안 요구로 문서화
             */
            .andExpect(
                jsonPath(
                    "$.paths['/api/auth/reset-password']"
                        + ".post.security[0].CsrfToken"
                ).isArray()
            )

            /*
             * DTO의 @Email 제약이 런타임 스키마의
             * format=email로 반영되는지 확인
             */
            .andExpect(
                jsonPath(
                    "$.components.schemas.ResetPasswordRequest"
                        + ".properties.email.format"
                ).value("email")
            )

            /*
             * DTO의 @Size(max = 100) 제약이 런타임 스키마의
             * maxLength=100으로 반영되는지 확인
             */
            .andExpect(
                jsonPath(
                    "$.components.schemas.ResetPasswordRequest"
                        + ".properties.email.maxLength"
                ).value(100)
            )

            /*
             * email 필드는 선택값이 아니라 필수 요청 필드여야 한다.
             */
            .andExpect(
                jsonPath(
                    "$.components.schemas.ResetPasswordRequest"
                        + ".required"
                ).value(
                    org.hamcrest.Matchers.hasItem(
                        "email"
                    )
                )
            );
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

    /** 런타임이 생성한 OpenAPI 문서를 읽습니다. */
    private Map<String, Object> fetchRuntimeDocument() throws Exception {
        String runtimeJson = mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readValue(runtimeJson, new TypeReference<>() {
        });
    }

    /**
     * 계약에 있는 operation 이 실제로 제공되는지 확인합니다.
     *
     * <p>반대 방향만 검사하면 계약에만 있고 구현이 없는 operation 이 그대로 통과합니다.
     * 프론트엔드는 계약을 보고 호출하므로, 그 상태가 오래 유지되면 없는 API 를 부르는 코드가
     * 배포됩니다.
     *
     * <p>아직 구현하지 않은 operation 은 계약에 {@code x-implementation-status: planned} 를
     * 붙여 제외합니다. 계약에서 지우지 않고 남겨 두려면 그 사실이 계약 파일에 드러나야 합니다.
     */
    @Test
    void agreedOperationsAreImplemented() throws Exception {
        Map<String, Object> runtime = fetchRuntimeDocument();

        List<String> differences = compareAgreedOperations(
            httpOperations(map(runtime, "paths")), map(contract, "paths"));

        assertThat(differences)
            .withFailMessage(() -> "계약과 구현 불일치:\n- " + String.join("\n- ", differences))
            .isEmpty();
    }

    /**
     * 계약에 있는 operation 이 런타임에 있는지 대조합니다.
     *
     * <p>{@code planned} 로 표시한 operation 은 구현이 없어도 통과시키되, 반대로 구현이
     * 생겼는데 표시가 남아 있으면 알립니다. 그대로 두면 계약의 표시가 실제와 어긋난 채
     * 굳습니다.
     */
    static List<String> compareAgreedOperations(
        Set<String> runtimeOperations, Map<String, Object> agreedPaths
    ) {
        List<String> differences = new ArrayList<>();
        agreedOperations(agreedPaths).forEach((operation, planned) -> {
            if (planned) {
                if (runtimeOperations.contains(operation)) {
                    differences.add(operation + " 이(가) 구현되었는데 계약에 planned 로 남아 있음");
                }
                return;
            }
            if (!runtimeOperations.contains(operation)) {
                differences.add(operation + " 이(가) 계약에만 있고 구현이 없음");
            }
        });
        return differences;
    }

    /**
     * 계약의 operation 과 계획 표시 여부입니다.
     *
     * <p>{@code x-implementation-status} 는 OpenAPI 확장 필드입니다. {@code planned} 외의 값은
     * 오타일 가능성이 커서 구현 대상으로 봅니다. 그래야 값이 틀렸을 때 조용히 제외되지 않고
     * 검사에 걸립니다.
     */
    private static Map<String, Boolean> agreedOperations(Map<String, Object> paths) {
        Map<String, Boolean> operations = new LinkedHashMap<>();
        paths.forEach((path, pathValue) -> {
            if (!path.startsWith("/api/")) {
                return;
            }
            Map<String, Object> pathItem = asMap(pathValue);
            HTTP_METHODS.stream()
                .filter(pathItem::containsKey)
                .forEach(method -> operations.put(
                    method.toUpperCase() + " " + path,
                    "planned".equals(asMap(pathItem.get(method)).get("x-implementation-status"))));
        });
        return operations;
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
