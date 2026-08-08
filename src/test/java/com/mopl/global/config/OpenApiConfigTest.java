package com.mopl.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.Test;

class OpenApiConfigTest {

    private final OpenApiConfig config = new OpenApiConfig();

    @Test
    void configuresDocumentMetadataAndSecuritySchemes() {
        OpenAPI openApi = config.moplOpenApi();

        assertThat(openApi.getInfo().getTitle()).isEqualTo("모두의 플리 (Mopl) API");
        assertThat(openApi.getInfo().getVersion()).isEqualTo("1.0");
        assertThat(openApi.getServers())
            .extracting(server -> server.getUrl())
            .containsExactly("/", "http://localhost:8080");

        SecurityScheme bearer = openApi.getComponents().getSecuritySchemes()
            .get(OpenApiConfig.BEARER_AUTH);
        assertThat(bearer.getType()).isEqualTo(SecurityScheme.Type.HTTP);
        assertThat(bearer.getScheme()).isEqualTo("bearer");
        assertThat(bearer.getBearerFormat()).isEqualTo("JWT");

        SecurityScheme csrf = openApi.getComponents().getSecuritySchemes()
            .get(OpenApiConfig.CSRF_TOKEN);
        assertThat(csrf.getType()).isEqualTo(SecurityScheme.Type.APIKEY);
        assertThat(csrf.getIn()).isEqualTo(SecurityScheme.In.HEADER);
        assertThat(csrf.getName()).isEqualTo("X-XSRF-TOKEN");
    }

    @Test
    void documentsJwtAndCsrfRequirementsByOperation() {
        Operation signUp = new Operation();
        Operation signIn = new Operation();
        Operation csrfToken = new Operation();
        Operation createReview = new Operation();
        Operation findReviews = new Operation();
        OpenAPI target = new OpenAPI().paths(new Paths()
            .addPathItem("/api/users", new PathItem().post(signUp))
            .addPathItem("/api/auth/sign-in", new PathItem().post(signIn))
            .addPathItem("/api/auth/csrf-token", new PathItem().get(csrfToken))
            .addPathItem("/api/reviews", new PathItem()
                .get(findReviews)
                .post(createReview)));

        config.operationSecurityCustomizer().customise(target);

        assertThat(signUp.getSecurity()).singleElement()
            .satisfies(requirement -> assertThat(requirement)
                .containsKey(OpenApiConfig.CSRF_TOKEN)
                .doesNotContainKey(OpenApiConfig.BEARER_AUTH));
        assertThat(signIn.getSecurity()).singleElement()
            .satisfies(requirement -> assertThat(requirement)
                .containsKey(OpenApiConfig.CSRF_TOKEN)
                .doesNotContainKey(OpenApiConfig.BEARER_AUTH));
        assertThat(csrfToken.getSecurity()).isEmpty();
        assertThat(createReview.getSecurity()).singleElement()
            .satisfies(requirement -> assertThat(requirement)
                .containsKeys(OpenApiConfig.BEARER_AUTH, OpenApiConfig.CSRF_TOKEN));
        assertThat(findReviews.getSecurity()).isNull();
    }
}
