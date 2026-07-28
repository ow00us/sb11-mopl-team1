package com.mopl.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    private final OpenAPI openAPI = new OpenApiConfig().moplOpenApi();

    @Test
    void 문서_기본_정보와_서버를_설정한다() {
        assertThat(openAPI.getInfo().getTitle()).isEqualTo("모두의 플리 (Mopl) API");
        assertThat(openAPI.getInfo().getVersion()).isEqualTo("1.0");
        assertThat(openAPI.getServers())
                .extracting(server -> server.getUrl())
                .containsExactly("/", "http://localhost:8080");
    }

    @Test
    void JWT_인증_방식을_설정한다() {
        SecurityScheme bearerAuth = openAPI.getComponents()
                .getSecuritySchemes()
                .get(OpenApiConfig.BEARER_AUTH);

        assertThat(bearerAuth.getType()).isEqualTo(SecurityScheme.Type.HTTP);
        assertThat(bearerAuth.getScheme()).isEqualTo("bearer");
        assertThat(bearerAuth.getBearerFormat()).isEqualTo("JWT");
        assertThat(openAPI.getSecurity().get(0))
                .containsKey(OpenApiConfig.BEARER_AUTH);
    }

    @Test
    void 쿠키와_함께_사용할_CSRF_헤더를_문서화한다() {
        SecurityScheme csrfToken = openAPI.getComponents()
                .getSecuritySchemes()
                .get(OpenApiConfig.CSRF_TOKEN);

        assertThat(csrfToken.getType()).isEqualTo(SecurityScheme.Type.APIKEY);
        assertThat(csrfToken.getIn()).isEqualTo(SecurityScheme.In.HEADER);
        assertThat(csrfToken.getName()).isEqualTo("X-XSRF-TOKEN");
    }

    @Test
    void 요청_종류에_맞게_JWT와_CSRF_조건을_표시한다() {
        Operation signUp = new Operation();
        Operation csrfToken = new Operation();
        Operation createReview = new Operation();
        Operation findReviews = new Operation();
        OpenAPI target = new OpenAPI().paths(new Paths()
                .addPathItem("/api/users", new PathItem().post(signUp))
                .addPathItem("/api/auth/csrf-token", new PathItem().get(csrfToken))
                .addPathItem("/api/reviews", new PathItem()
                        .get(findReviews)
                        .post(createReview)));

        new OpenApiConfig().operationSecurityCustomizer().customise(target);

        assertThat(signUp.getSecurity()).singleElement()
                .satisfies(requirement -> assertThat(requirement)
                        .containsKey(OpenApiConfig.CSRF_TOKEN)
                        .doesNotContainKey(OpenApiConfig.BEARER_AUTH));
        assertThat(csrfToken.getSecurity()).isEmpty();
        assertThat(createReview.getSecurity()).singleElement()
                .satisfies(requirement -> assertThat(requirement)
                        .containsKeys(
                                OpenApiConfig.BEARER_AUTH,
                                OpenApiConfig.CSRF_TOKEN
                        ));
        assertThat(findReviews.getSecurity()).isNull();
    }
}
