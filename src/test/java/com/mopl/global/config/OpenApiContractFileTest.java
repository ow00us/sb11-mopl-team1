package com.mopl.global.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiContractFileTest {

    private static final Path REFERENCE =
            Path.of("openapi/reference/provided-openapi.json");
    private static final Path CONTRACT =
            Path.of("openapi/mopl-api.yaml");
    private static final String REFERENCE_SHA_256 =
            "3C9725909B44B3D3FC562F3D4A7551D215F9D0513A13B26143B2E5FAAB2943E1";

    private static Map<String, Object> contract;

    @BeforeAll
    static void loadContract() throws IOException {
        try (InputStream input = Files.newInputStream(CONTRACT)) {
            contract = new Yaml().load(input);
        }
    }

    @Test
    void 제공된_OpenAPI_원본을_그대로_보존한다()
            throws IOException, NoSuchAlgorithmException {
        byte[] content = Files.readAllBytes(REFERENCE);
        String actualHash = HexFormat.of().withUpperCase()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(content));

        assertThat(actualHash).isEqualTo(REFERENCE_SHA_256);
    }

    @Test
    void 합의본은_OpenAPI_3_1_문서로_읽힌다() {
        assertThat(contract.get("openapi")).isEqualTo("3.1.0");
        assertThat(map(contract, "info").get("title"))
                .isEqualTo("모두의 플리 (Mopl) API");
        assertThat(map(contract, "paths")).hasSize(32);
    }

    @Test
    void 합의한_필드와_정렬값을_사용한다() {
        Map<String, Object> paths = map(contract, "paths");

        assertThat(parameterNames(operation(paths, "/api/users", "get")))
                .contains("locked")
                .doesNotContain("isLocked");
        assertThat(parameterEnum(operation(paths, "/api/users", "get"), "sortBy"))
                .contains("locked")
                .doesNotContain("isLocked");
        assertThat(parameterEnum(operation(paths, "/api/contents", "get"), "sortBy"))
                .contains("rating")
                .doesNotContain("rate");
        assertThat(parameterEnum(operation(paths, "/api/playlists", "get"), "sortBy"))
                .contains("subscriberCount")
                .doesNotContain("subscribeCount");

        Map<String, Object> schemas =
                map(map(contract, "components"), "schemas");
        Map<String, Object> conversationProperties =
                map(map(schemas, "ConversationDto"), "properties");
        assertThat(conversationProperties)
                .containsKey("latestMessage")
                .doesNotContainKey("lastestMessage");
        List<Map<String, Object>> latestMessageTypes =
                listOfMaps(map(conversationProperties, "latestMessage"), "oneOf");
        assertThat(latestMessageTypes)
                .anySatisfy(schema -> assertThat(schema.get("$ref"))
                        .isEqualTo("#/components/schemas/DirectMessageDto"))
                .anySatisfy(schema -> assertThat(schema.get("type"))
                        .isEqualTo("null"));
        assertThat(list(map(schemas, "UserDto"), "required"))
                .contains("locked")
                .doesNotContain("isLocked");
    }

    @Test
    void 생성과_본문_없는_변경의_상태_코드를_통일한다() {
        Map<String, Object> paths = map(contract, "paths");

        for (String path : List.of(
                "/api/users",
                "/api/reviews",
                "/api/playlists",
                "/api/contents"
        )) {
            assertThat(responses(operation(paths, path, "post")))
                    .containsKey("201")
                    .doesNotContainKey("200");
        }

        for (String path : List.of("/api/follows", "/api/conversations")) {
            assertThat(responses(operation(paths, path, "post")))
                    .containsKeys("200", "201");
        }

        for (List<String> endpoint : List.of(
                List.of("/api/auth/sign-out", "post"),
                List.of("/api/reviews/{reviewId}", "delete"),
                List.of("/api/playlists/{playlistId}", "delete"),
                List.of("/api/contents/{contentId}", "delete"),
                List.of("/api/auth/csrf-token", "get")
        )) {
            assertThat(responses(operation(paths, endpoint.get(0), endpoint.get(1))))
                    .containsKey("204")
                    .doesNotContainKey("200");
        }
    }

    @Test
    void 거절해야_하는_중복만_409로_응답한다() {
        Map<String, Object> paths = map(contract, "paths");

        for (String path : List.of("/api/users", "/api/reviews")) {
            Map<String, Object> conflict =
                    map(responses(operation(paths, path, "post")), "409");
            Map<String, Object> schema =
                    map(conflict, "content", "*/*", "schema");

            assertThat(schema.get("$ref"))
                    .isEqualTo("#/components/schemas/ErrorResponse");
        }

        for (List<String> endpoint : List.of(
                List.of("/api/follows", "post"),
                List.of("/api/conversations", "post"),
                List.of("/api/playlists/{playlistId}/subscription", "post"),
                List.of(
                        "/api/playlists/{playlistId}/contents/{contentId}",
                        "post"
                )
        )) {
            assertThat(responses(operation(
                    paths, endpoint.get(0), endpoint.get(1))))
                    .doesNotContainKey("409");
        }
    }

    @Test
    void 요청_종류에_맞게_JWT와_CSRF_조건을_표시한다() {
        Map<String, Object> paths = map(contract, "paths");
        List<String> unsafeMethods = List.of("post", "put", "patch", "delete");
        List<String> publicPosts = List.of(
                "/api/users",
                "/api/auth/sign-in",
                "/api/auth/reset-password",
                "/api/auth/refresh"
        );

        paths.forEach((path, pathItemValue) -> {
            Map<String, Object> pathItem = asMap(pathItemValue);
            unsafeMethods.stream()
                    .filter(pathItem::containsKey)
                    .forEach(method -> {
                        Map<String, Object> requirement = listOfMaps(
                                asMap(pathItem.get(method)),
                                "security"
                        ).get(0);
                        assertThat(requirement).containsKey("CsrfToken");
                        if (method.equals("post") && publicPosts.contains(path)) {
                            assertThat(requirement).doesNotContainKey("BearerAuth");
                        } else {
                            assertThat(requirement).containsKey("BearerAuth");
                        }
                    });
        });

        assertThat(list(
                operation(paths, "/api/auth/csrf-token", "get"),
                "security"
        )).isEmpty();
        assertThat(operation(paths, "/api/reviews", "get"))
                .doesNotContainKey("security");
        assertThat(listOfMaps(contract, "security").get(0))
                .containsKey("BearerAuth")
                .doesNotContainKey("CsrfToken");
    }

    @Test
    void 공통_응답과_특수_요청_규칙을_반영한다() {
        Map<String, Object> paths = map(contract, "paths");
        Map<String, Object> schemas =
                map(map(contract, "components"), "schemas");

        Map<String, Object> contentUpdate =
                map(operation(paths, "/api/contents/{contentId}", "patch"), "requestBody");
        assertThat(map(contentUpdate, "content"))
                .containsKey("multipart/form-data")
                .doesNotContainKey("application/json");

        assertThat(responses(operation(
                paths, "/api/users/{watcherId}/watching-sessions", "get")))
                .containsKeys("200", "204");

        Map<String, Object> followerCountSchema = map(
                map(responses(operation(paths, "/api/follows/count", "get")), "200"),
                "content",
                "*/*",
                "schema"
        );
        assertThat(followerCountSchema.get("$ref"))
                .isEqualTo("#/components/schemas/FollowerCountResponse");

        assertThat(map(map(schemas, "ErrorResponse"), "properties"))
                .containsKey("errorCode");
        assertThat(map(map(schemas, "NotificationDto"), "properties"))
                .containsKey("readAt");

        schemas.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("CursorResponse"))
                .map(entry -> map(asMap(entry.getValue()), "properties"))
                .forEach(properties -> {
                    assertThat(list(map(properties, "nextCursor"), "type"))
                            .containsExactly("string", "null");
                    assertThat(list(map(properties, "nextIdAfter"), "type"))
                            .containsExactly("string", "null");
                });

        assertThat(map(responses(operation(
                paths,
                "/api/playlists/{playlistId}/subscription",
                "post"
        )), "204").get("description"))
                .isEqualTo("구독 완료. 이미 구독 중이어도 성공");
        assertThat(map(responses(operation(
                paths,
                "/api/playlists/{playlistId}/contents/{contentId}",
                "post"
        )), "204").get("description"))
                .isEqualTo("추가 완료. 이미 포함된 콘텐츠여도 성공");
    }

    @Test
    void 미확정_계약은_기존_형태를_유지한다() {
        Map<String, Object> paths = map(contract, "paths");

        assertThat(map(paths, "/api/notifications/{notificationId}"))
                .containsKey("delete");
        assertThat(paths).doesNotContainKey("/api/users/me");
        assertThat(responses(operation(
                paths, "/api/follows/followed-by-me", "get")))
                .containsKey("404");
    }

    private static Map<String, Object> operation(
            Map<String, Object> paths,
            String path,
            String method
    ) {
        return map(map(paths, path), method);
    }

    private static Map<String, Object> responses(Map<String, Object> operation) {
        return map(operation, "responses");
    }

    private static List<String> parameterNames(Map<String, Object> operation) {
        return listOfMaps(operation, "parameters").stream()
                .map(parameter -> (String) parameter.get("name"))
                .toList();
    }

    private static List<Object> parameterEnum(
            Map<String, Object> operation,
            String parameterName
    ) {
        Map<String, Object> parameter = listOfMaps(operation, "parameters").stream()
                .filter(candidate -> parameterName.equals(candidate.get("name")))
                .findFirst()
                .orElseThrow();
        return list(map(parameter, "schema"), "enum");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object source) {
        return (Map<String, Object>) source;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(
            Map<String, Object> source,
            String... keys
    ) {
        Map<String, Object> current = source;
        for (String key : keys) {
            current = (Map<String, Object>) current.get(key);
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Map<String, Object> source, String key) {
        return (List<Object>) source.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(
            Map<String, Object> source,
            String key
    ) {
        return (List<Map<String, Object>>) source.get(key);
    }
}
