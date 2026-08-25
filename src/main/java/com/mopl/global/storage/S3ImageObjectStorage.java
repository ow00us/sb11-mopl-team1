package com.mopl.global.storage;

import com.mopl.global.exception.ErrorCode;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * 이미지를 S3 에 올립니다.
 *
 * <p>애플리케이션 컨테이너의 로컬 파일에 쓰지 않습니다. 인스턴스가 둘이면 A 가 저장한 파일을
 * B 가 읽지 못하고, 컨테이너를 다시 만들면 사라집니다.
 *
 * <p>검증을 먼저 하고 올립니다. 형식과 크기를 확인하기 전에 바이트를 보내면 거부할 파일의
 * 전송 비용과 저장 공간을 함께 씁니다.
 */
@Slf4j
public class S3ImageObjectStorage implements ImageObjectStorage {

    private final S3Client s3Client;
    private final ImageStorageProperties properties;

    public S3ImageObjectStorage(S3Client s3Client, ImageStorageProperties properties) {
        this.s3Client = s3Client;
        this.properties = properties;
    }

    @Override
    public String upload(MultipartFile file, String prefix) {
        String contentType = validate(file);
        String key = ImageObjectKeyFactory.create(prefix, contentType);

        try {
            s3Client.putObject(
                PutObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .contentType(contentType)
                    .contentLength(file.getSize())
                    .build(),
                RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        } catch (IOException | RuntimeException e) {
            // 올리지 못했으면 URL 이 없습니다. 여기서 끊어야 호출한 도메인 트랜잭션이
            // 이미지 없는 레코드를 커밋하지 않습니다.
            log.error("이미지를 S3 에 올리지 못했습니다. key={}", key, e);
            throw new ImageUploadException(ErrorCode.IMAGE_UPLOAD_FAILED,
                "이미지를 저장하지 못했습니다.");
        }

        return publicUrlOf(key);
    }

    /**
     * 올릴 수 있는 파일인지 봅니다.
     *
     * @return 검증을 마친 Content-Type
     */
    private String validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ImageUploadException(ErrorCode.IMAGE_UPLOAD_REJECTED,
                "빈 이미지 파일입니다.");
        }
        if (file.getSize() > properties.maxFileSize()) {
            throw new ImageUploadException(ErrorCode.IMAGE_UPLOAD_REJECTED,
                "이미지 크기가 허용 범위를 넘었습니다. 최대 " + properties.maxFileSize() + "바이트");
        }

        String contentType = file.getContentType();
        if (contentType == null || !properties.allowedContentTypes().contains(contentType)) {
            // 원본 파일명은 로그에 남기지 않습니다. 사용자가 정하는 값입니다.
            throw new ImageUploadException(ErrorCode.IMAGE_UPLOAD_REJECTED,
                "허용하지 않는 이미지 형식입니다. contentType=" + contentType);
        }
        return contentType;
    }

    /**
     * 조회 URL 을 만듭니다.
     *
     * <p>버킷 주소를 코드에서 조립하지 않고 설정에서 받습니다. 앞에 CDN 을 두면 그 주소만
     * 바꾸면 되고, 저장 위치와 조회 위치가 달라도 됩니다.
     */
    private String publicUrlOf(String key) {
        String base = properties.publicBaseUrl();
        return base.endsWith("/") ? base + key : base + "/" + key;
    }
}
