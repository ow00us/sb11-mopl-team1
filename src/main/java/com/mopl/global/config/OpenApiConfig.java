package com.mopl.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    static final String BEARER_AUTH = "BearerAuth";
    static final String CSRF_TOKEN = "CsrfToken";
    private static final String CSRF_TOKEN_PATH = "/api/auth/csrf-token";

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
                        .description("팀이 합의한 요청·응답 규칙을 따르는 Mopl API 문서입니다.")
                        .version("1.0"))
                .servers(List.of(
                        new Server().url("/").description("현재 서버"),
                        new Server().url("http://localhost:8080").description("로컬 서버")
                ))
                .components(components)
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }

    @Bean
    public OpenApiCustomizer operationSecurityCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }

            openApi.getPaths().forEach((path, pathItem) ->
                    pathItem.readOperationsMap().forEach((method, operation) -> {
                        if (CSRF_TOKEN_PATH.equals(path) && method == PathItem.HttpMethod.GET) {
                            operation.setSecurity(List.of());
                            return;
                        }

                        if (!isUnsafe(method)) {
                            return;
                        }

                        SecurityRequirement requirement = new SecurityRequirement()
                                .addList(CSRF_TOKEN);
                        if (!isPublicAuthenticationRequest(path, method)) {
                            requirement.addList(BEARER_AUTH);
                        }
                        operation.setSecurity(List.of(requirement));
                    }));
        };
    }

    private boolean isUnsafe(PathItem.HttpMethod method) {
        return method == PathItem.HttpMethod.POST
                || method == PathItem.HttpMethod.PUT
                || method == PathItem.HttpMethod.PATCH
                || method == PathItem.HttpMethod.DELETE;
    }

    private boolean isPublicAuthenticationRequest(
            String path,
            PathItem.HttpMethod method
    ) {
        if (method != PathItem.HttpMethod.POST) {
            return false;
        }
        return "/api/users".equals(path)
                || "/api/auth/sign-in".equals(path)
                || "/api/auth/reset-password".equals(path)
                || "/api/auth/refresh".equals(path);
    }
}
