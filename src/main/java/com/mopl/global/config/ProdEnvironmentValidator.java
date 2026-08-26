package com.mopl.global.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.PlaceholderResolutionException;
import org.springframework.util.StringUtils;

/**
 * 운영 설정이 실제로 쓸 수 있는 값인지 기동 시점에 확인합니다.
 *
 * <p>{@code ${...}} 는 값이 있는지까지만 봅니다. 값이 있어도 형식이 틀리면 그 사실은 사용자가
 * 그 경로를 밟을 때 처음 드러납니다. OAuth callback URI 에 상대 경로가 들어가면 로그인 시도가
 * 있을 때까지, 잘못된 origin 이 들어가면 브라우저 요청이 올 때까지 알 수 없습니다.
 *
 * <p>문제를 모아서 한 번에 보고합니다. 하나씩 던지면 운영자가 고치고 다시 띄우기를 값의 수만큼
 * 반복해야 합니다. 배포 중에 그 반복은 그대로 중단 시간입니다.
 *
 * <p>{@code prod} 에서만 돕니다. 로컬과 테스트는 localhost 주소를 쓰는 것이 정상입니다.
 */
@Component
@Profile("prod")
public class ProdEnvironmentValidator implements InitializingBean {

    /** 절대 URI 여야 하는 설정입니다. 값 자체가 외부에 노출되는 주소입니다. */
    private static final List<String> ABSOLUTE_URI_KEYS = List.of(
        "app.oauth2.redirect.success-uri",
        "app.oauth2.redirect.failure-uri",
        "spring.security.oauth2.client.registration.google.redirect-uri",
        "spring.security.oauth2.client.registration.kakao.redirect-uri",
        "spring.security.oauth2.client.registration.naver.redirect-uri");

    /** 콤마로 구분한 origin 목록입니다. 각 항목이 scheme 과 host 를 갖춰야 합니다. */
    private static final List<String> ORIGIN_LIST_KEYS = List.of(
        "app.cors.allowed-origins",
        "app.websocket.allowed-origins");

    private static final String IMAGE_STORAGE_ENABLED_KEY = "mopl.storage.image.enabled";
    private static final String IMAGE_STORAGE_BUCKET_KEY = "mopl.storage.image.bucket";
    private static final String IMAGE_STORAGE_PUBLIC_BASE_URL_KEY =
        "mopl.storage.image.public-base-url";

    private final Environment environment;

    public ProdEnvironmentValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> problems = new ArrayList<>();

        ABSOLUTE_URI_KEYS.forEach(key -> validateAbsoluteUri(key, problems));
        ORIGIN_LIST_KEYS.forEach(key -> validateOrigins(key, problems));
        validateImageStorage(problems);

        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                "운영 설정이 올바르지 않습니다." + System.lineSeparator()
                    + "- " + String.join(System.lineSeparator() + "- ", problems));
        }
    }

    private void validateAbsoluteUri(String key, List<String> problems) {
        String value = resolve(key, problems);
        if (!StringUtils.hasText(value)) {
            // 값 자체가 없는 경우는 바인딩이 이미 막습니다. 여기서 또 보고하면 같은 문제가
            // 두 번 나옵니다.
            return;
        }

        URI uri = parse(value);
        if (uri == null || !uri.isAbsolute() || uri.getHost() == null) {
            problems.add(key + " 는 scheme 과 host 를 갖춘 절대 URI 여야 합니다. 실제 " + value);
        }
    }

    /**
     * origin 목록을 확인합니다.
     *
     * <p>origin 은 scheme 과 host 까지입니다. 경로가 붙으면 Spring Security 가 어떤 요청과도
     * 맞추지 못해, 설정은 있는데 모든 브라우저 요청이 막힙니다.
     */
    private void validateOrigins(String key, List<String> problems) {
        String value = resolve(key, problems);
        if (!StringUtils.hasText(value)) {
            return;
        }

        for (String origin : value.split(",")) {
            String trimmed = origin.strip();
            if (trimmed.isEmpty()) {
                problems.add(key + " 에 빈 항목이 있습니다. 실제 " + value);
                continue;
            }

            URI uri = parse(trimmed);
            if (uri == null || !uri.isAbsolute() || uri.getHost() == null) {
                problems.add(key + " 의 항목이 scheme 과 host 를 갖춘 origin 이 아닙니다. 실제 "
                    + trimmed);
                continue;
            }
            if (StringUtils.hasText(uri.getPath())) {
                problems.add(key + " 의 항목에 경로가 붙어 있습니다. origin 은 scheme 과 host 까지입니다. 실제 "
                    + trimmed);
            }
        }
    }

    /**
     * 이미지 저장소를 켠 채로 대상이 비어 있는지 확인합니다.
     *
     * <p>{@code ImageStorageProperties} 도 같은 것을 보지만, 바인딩은 풀리지 않은
     * {@code ${...}} 를 문자열 그대로 넣습니다. 그래서 비어 있는지만 보는 검사는 통과하고,
     * 존재하지 않는 버킷 이름을 들고 기동합니다. 그 사실은 사용자가 파일을 고른 뒤에야
     * 드러납니다.
     */
    private void validateImageStorage(List<String> problems) {
        // prod 기본값은 true 입니다. 명시적으로 끈 경우에만 대상이 없어도 됩니다.
        if (!environment.getProperty(IMAGE_STORAGE_ENABLED_KEY, Boolean.class, true)) {
            return;
        }

        requireText(IMAGE_STORAGE_BUCKET_KEY, problems);

        String publicBaseUrl = requireText(IMAGE_STORAGE_PUBLIC_BASE_URL_KEY, problems);
        if (publicBaseUrl == null) {
            return;
        }

        URI uri = parse(publicBaseUrl);
        if (uri == null || !uri.isAbsolute() || uri.getHost() == null) {
            problems.add(IMAGE_STORAGE_PUBLIC_BASE_URL_KEY
                + " 는 scheme 과 host 를 갖춘 절대 URI 여야 합니다. 실제 " + publicBaseUrl);
        }
    }

    /** 값이 있어야 하는 설정을 읽습니다. 없으면 문제로 남기고 {@code null} 을 돌려줍니다. */
    private String requireText(String key, List<String> problems) {
        int reported = problems.size();
        String value = resolve(key, problems);
        if (problems.size() > reported) {
            return null;
        }
        if (!StringUtils.hasText(value)) {
            problems.add(key + " 가 비어 있습니다. "
                + IMAGE_STORAGE_ENABLED_KEY + " 가 true 이면 필요합니다.");
            return null;
        }
        return value;
    }

    /**
     * 값을 읽되, 채울 환경 변수가 없으면 그것도 문제 목록에 넣습니다.
     *
     * <p>{@code Environment} 는 풀리지 않은 자리표시자를 만나면 그 자리에서 던집니다. 그대로
     * 두면 뒤에 있는 값은 보지도 못하고, 운영자가 한 번에 하나씩 고치며 다시 띄우게 됩니다.
     */
    private String resolve(String key, List<String> problems) {
        try {
            return environment.getProperty(key);
        } catch (PlaceholderResolutionException e) {
            problems.add(key + " 를 채울 환경 변수가 없습니다. " + e.getMessage());
            return null;
        }
    }

    private URI parse(String value) {
        try {
            return URI.create(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
