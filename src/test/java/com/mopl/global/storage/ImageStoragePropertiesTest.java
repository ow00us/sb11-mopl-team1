package com.mopl.global.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

class ImageStoragePropertiesTest {

    private static final String BUCKET = "test-images";
    private static final String PUBLIC_BASE_URL = "https://images.example.test";

    private ApplicationContextRunner contextRunner(boolean enabled, String bucket, String baseUrl) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("mopl.storage.image.enabled", enabled);
        properties.put("mopl.storage.image.region", "ap-northeast-2");
        properties.put("mopl.storage.image.profile-image-prefix", "profiles");
        properties.put("mopl.storage.image.thumbnail-prefix", "thumbnails");
        properties.put("mopl.storage.image.max-file-size", 1024);
        properties.put("mopl.storage.image.allowed-content-types", "image/png,image/jpeg");
        if (bucket != null) {
            properties.put("mopl.storage.image.bucket", bucket);
        }
        if (baseUrl != null) {
            properties.put("mopl.storage.image.public-base-url", baseUrl);
        }
        return new ApplicationContextRunner()
            .withUserConfiguration(BindingConfiguration.class)
            .withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("image-storage-test", properties)));
    }

    private static Stream<Arguments> missingTargets() {
        return Stream.of(
            Arguments.of("bucket missing", null, PUBLIC_BASE_URL),
            Arguments.of("bucket empty", "", PUBLIC_BASE_URL),
            Arguments.of("bucket blank", " \t", PUBLIC_BASE_URL),
            Arguments.of("public URL missing", BUCKET, null),
            Arguments.of("public URL empty", BUCKET, ""),
            Arguments.of("public URL blank", BUCKET, " \t"),
            Arguments.of("both missing", null, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("missingTargets")
    @DisplayName("S3가 켜져 있으면 대상 설정의 누락·빈값·공백이 실제 바인딩에서 거부된다")
    void enabledStorageRejectsMissingTargets(String scenario, String bucket, String baseUrl) {
        contextRunner(true, bucket, baseUrl).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(BindValidationException.class)
                .hasStackTraceContaining("bucket 과 public-base-url 이 필요합니다");
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("missingTargets")
    @DisplayName("S3를 끄면 같은 대상 설정 누락으로 로컬 기동을 막지 않는다")
    void disabledStorageDoesNotRequireTargets(String scenario, String bucket, String baseUrl) {
        contextRunner(false, bucket, baseUrl).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ImageStorageProperties.class);
            assertThat(context.getBean(ImageStorageProperties.class).enabled()).isFalse();
        });
    }

    @Test
    @DisplayName("S3 대상과 파일 검증 설정이 정상적으로 바인딩된다")
    void validTargetsAndFilePolicyBind() {
        contextRunner(true, BUCKET, PUBLIC_BASE_URL).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ImageStorageProperties.class);
            ImageStorageProperties properties = context.getBean(ImageStorageProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.bucket()).isEqualTo(BUCKET);
            assertThat(properties.publicBaseUrl()).isEqualTo(PUBLIC_BASE_URL);
            assertThat(properties.region()).isEqualTo("ap-northeast-2");
            assertThat(properties.profileImagePrefix()).isEqualTo("profiles");
            assertThat(properties.thumbnailPrefix()).isEqualTo("thumbnails");
            assertThat(properties.maxFileSize()).isEqualTo(1024);
            assertThat(properties.allowedContentTypes())
                .containsExactlyInAnyOrder("image/png", "image/jpeg");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ImageStorageProperties.class)
    static class BindingConfiguration {
    }
}
