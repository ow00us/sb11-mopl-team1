package com.mopl.global.storage;

import com.mopl.global.exception.ErrorCode;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

/**
 * 파일을 저장하지 않고 URL 모양만 돌려줍니다.
 *
 * <p>로컬 개발과 테스트에서 씁니다. AWS 자격 증명 없이 도메인 흐름을 돌려 볼 수 있어야 하고,
 * 테스트가 실제 버킷에 객체를 남기면 안 됩니다.
 *
 * <p>검증은 그대로 합니다. 빈 파일이나 허용하지 않는 형식이 로컬에서만 통과하면 그 차이가
 * 운영에서 처음 드러납니다.
 *
 * <p>돌려주는 주소는 실제로 열리지 않습니다. 이 구현으로 올린 이미지는 조회되지 않는 것이
 * 정상이고, 그 사실이 주소에 드러나야 운영 설정이 빠진 것을 알아차립니다.
 */
@Slf4j
public class PlaceholderImageObjectStorage implements ImageObjectStorage {

    private static final String BASE_URL = "https://placeholder.mopl.local/";

    private final ImageStorageProperties properties;

    public PlaceholderImageObjectStorage(ImageStorageProperties properties) {
        this.properties = properties;
    }

    @Override
    public String upload(MultipartFile file, String prefix) {
        if (file == null || file.isEmpty()) {
            throw new ImageUploadException(ErrorCode.IMAGE_UPLOAD_REJECTED, "빈 이미지 파일입니다.");
        }

        String contentType = file.getContentType();
        if (contentType == null || !properties.allowedContentTypes().contains(contentType)) {
            throw new ImageUploadException(ErrorCode.IMAGE_UPLOAD_REJECTED,
                "허용하지 않는 이미지 형식입니다. contentType=" + contentType);
        }
        if (file.getSize() > properties.maxFileSize()) {
            throw new ImageUploadException(ErrorCode.IMAGE_UPLOAD_REJECTED,
                "이미지 크기가 허용 범위를 넘었습니다.");
        }

        log.debug("이미지 저장소가 꺼져 있어 파일을 저장하지 않습니다. prefix={}", prefix);
        return BASE_URL + prefix + "/" + UUID.randomUUID();
    }
}
