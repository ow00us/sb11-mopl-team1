package com.mopl.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 런타임 OpenAPI 문서의 공통 정보와 HTTP 보안 요구를 설정합니다. */
@Configuration
public class OpenApiConfig {

    static final String BEARER_AUTH = "BearerAuth";
    static final String CSRF_TOKEN = "CsrfToken";

    @Bean
    public OpenAPI moplOpenApi() {
        Components components = new Components()
            .addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("로그인 후 발급받은 JWT 액세스 토큰"))
            .addSecuritySchemes(CSRF_TOKEN, new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name("X-XSRF-TOKEN")
                .description("XSRF-TOKEN 쿠키로 받은 CSRF 토큰 값"));

        return new OpenAPI()
            .info(new Info()
                .title("모두의 플리 (Mopl) API")
                .description("프로토타입 OpenAPI를 기준으로 팀이 합의한 요청·응답 규칙을 반영한 API 계약입니다.")
                .version("1.0"))
            .servers(List.of(
                new Server().url("/").description("현재 서버"),
                new Server().url("http://localhost:8080").description("로컬 서버")
            ))
            .components(components)
            .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }

    /** SecurityConfig의 공개 경로와 CSRF 정책을 런타임 문서에 반영합니다. */
    @Bean
    public OpenApiCustomizer operationSecurityCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }

            openApi.getPaths().forEach((path, pathItem) ->
                pathItem.readOperationsMap().forEach((method, operation) -> {
                    if (method == PathItem.HttpMethod.GET
                        && SecurityConfig.isPublicGetPath(path)) {
                        operation.setSecurity(List.of());
                        return;
                    }

                    if (!isUnsafe(method)) {
                        return;
                    }

                    SecurityRequirement requirement = new SecurityRequirement()
                        .addList(CSRF_TOKEN);
                    if (!isPublicPost(path, method)) {
                        requirement.addList(BEARER_AUTH);
                    }
                    operation.setSecurity(List.of(requirement));
                })
            );
        };
    }

    private boolean isUnsafe(PathItem.HttpMethod method) {
        return method == PathItem.HttpMethod.POST
            || method == PathItem.HttpMethod.PUT
            || method == PathItem.HttpMethod.PATCH
            || method == PathItem.HttpMethod.DELETE;
    }

    private boolean isPublicPost(String path, PathItem.HttpMethod method) {
        return method == PathItem.HttpMethod.POST
            && SecurityConfig.isPublicPostPath(path);
    }
}
