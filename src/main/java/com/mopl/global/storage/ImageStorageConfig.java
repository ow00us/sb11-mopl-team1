package com.mopl.global.storage;

import com.mopl.content.storage.ThumbnailStorage;
import com.mopl.user.storage.ProfileImageStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 이미지 저장소를 구성합니다.
 *
 * <p>{@code mopl.storage.image.enabled} 로 나눕니다. 켜면 S3 에 올리고, 끄면 파일을 저장하지
 * 않는 구현이 붙습니다. 로컬 개발과 테스트가 AWS 자격 증명 없이 돌아야 합니다.
 *
 * <p>도메인이 보는 인터페이스는 그대로 둡니다. {@code ProfileImageStorage} 와
 * {@code ThumbnailStorage} 는 저장 위치를 모르고, 여기서 구분자만 다르게 묶습니다.
 */
@Configuration
@EnableConfigurationProperties(ImageStorageProperties.class)
public class ImageStorageConfig {

    /**
     * S3 클라이언트입니다.
     *
     * <p>자격 증명을 코드나 환경 변수로 고정하지 않습니다. 기본 자격 증명 체인이 EC2
     * 인스턴스 역할을 먼저 찾으므로, 서버에 장기 access key 를 두지 않아도 됩니다.
     */
    @Bean
    @ConditionalOnProperty(name = "mopl.storage.image.enabled", havingValue = "true")
    public S3Client s3Client(ImageStorageProperties properties) {
        return S3Client.builder()
            .region(Region.of(properties.region()))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build();
    }

    @Bean
    @ConditionalOnProperty(name = "mopl.storage.image.enabled", havingValue = "true")
    public ImageObjectStorage s3ImageObjectStorage(
        S3Client s3Client, ImageStorageProperties properties
    ) {
        return new S3ImageObjectStorage(s3Client, properties);
    }

    @Bean
    @ConditionalOnProperty(
        name = "mopl.storage.image.enabled", havingValue = "false", matchIfMissing = true)
    public ImageObjectStorage placeholderImageObjectStorage(ImageStorageProperties properties) {
        return new PlaceholderImageObjectStorage(properties);
    }

    @Bean
    public ProfileImageStorage profileImageStorage(
        ImageObjectStorage imageObjectStorage, ImageStorageProperties properties
    ) {
        return image -> imageObjectStorage.upload(image, properties.profileImagePrefix());
    }

    @Bean
    public ThumbnailStorage thumbnailStorage(
        ImageObjectStorage imageObjectStorage, ImageStorageProperties properties
    ) {
        return file -> imageObjectStorage.upload(file, properties.thumbnailPrefix());
    }
}
