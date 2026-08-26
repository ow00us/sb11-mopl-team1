package com.mopl.global.storage;

import jakarta.validation.constraints.AssertTrue;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import org.springframework.util.StringUtils;

/**
 * 이미지 저장소 설정입니다.
 *
 * @param enabled S3 어댑터 사용 여부. 끄면 파일을 저장하지 않는 대체 구현이 붙습니다
 * @param bucket 버킷 이름
 * @param region 버킷이 있는 리전
 * @param publicBaseUrl 조회 URL 의 앞부분. CDN 을 두면 그 주소입니다
 * @param profileImagePrefix 프로필 이미지 객체 키 구분자
 * @param thumbnailPrefix 콘텐츠 썸네일 객체 키 구분자
 * @param maxFileSize 허용하는 최대 파일 크기(바이트)
 * @param allowedContentTypes 허용하는 Content-Type 목록
 */
@Validated
@ConfigurationProperties(prefix = "mopl.storage.image")
public record ImageStorageProperties(
    boolean enabled,
    String bucket,
    String region,
    String publicBaseUrl,
    String profileImagePrefix,
    String thumbnailPrefix,
    long maxFileSize,
    Set<String> allowedContentTypes
) {

    /**
     * 켜 두고 버킷이나 조회 주소를 비워 두면 업로드 시점에야 드러납니다.
     *
     * <p>그때는 이미 사용자가 파일을 고른 뒤이고, 실패 원인이 설정 누락이라는 것도 로그를
     * 봐야 알 수 있습니다. 기동에서 막습니다.
     */
    @AssertTrue(message = "mopl.storage.image.enabled 가 true 이면 bucket 과 public-base-url 이 필요합니다.")
    public boolean isTargetConfiguredWhenEnabled() {
        return !enabled
            || (StringUtils.hasText(bucket) && StringUtils.hasText(publicBaseUrl));
    }
}
