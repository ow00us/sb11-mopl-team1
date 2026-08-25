package com.mopl.global.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * 저장소를 꺼 둔 환경의 대체 구현을 검증합니다.
 *
 * <p>검증 규칙이 S3 구현과 같아야 합니다. 빈 파일이나 허용하지 않는 형식이 로컬에서만
 * 통과하면 그 차이가 운영에서 처음 드러납니다.
 */
class PlaceholderImageObjectStorageTest {

    private static final ImageStorageProperties PROPERTIES = new ImageStorageProperties(
        false, "", "ap-northeast-2", "", "profile-images", "thumbnails",
        5_242_880L, Set.of("image/jpeg", "image/png"));

    private final ImageObjectStorage storage = new PlaceholderImageObjectStorage(PROPERTIES);

    private static MultipartFile image(String filename, String contentType, int size) {
        return new MockMultipartFile("file", filename, contentType, new byte[size]);
    }

    @Test
    @DisplayName("파일을 저장하지 않고 열리지 않는 주소를 돌려준다")
    void upload_returnsPlaceholderUrl() {
        String url = storage.upload(image("photo.png", "image/png", 10), "profile-images");

        assertThat(url).startsWith("https://placeholder.mopl.local/profile-images/");
    }

    @Test
    @DisplayName("원본 파일명을 주소에 남기지 않는다")
    void upload_doesNotLeakFilename() {
        String url = storage.upload(
            image("../../etc/passwd.png", "image/png", 10), "profile-images");

        assertThat(url).doesNotContain("passwd").doesNotContain("..");
    }

    @Test
    @DisplayName("같은 파일을 두 번 올려도 주소가 겹치지 않는다")
    void upload_returnsDistinctUrls() {
        MultipartFile file = image("photo.png", "image/png", 10);

        assertThat(storage.upload(file, "thumbnails"))
            .isNotEqualTo(storage.upload(file, "thumbnails"));
    }

    @Test
    @DisplayName("허용하지 않는 형식은 S3 구현과 같이 거부한다")
    void upload_rejectsDisallowedContentType() {
        assertThatThrownBy(() ->
            storage.upload(image("script.svg", "image/svg+xml", 10), "profile-images"))
            .isInstanceOf(ImageUploadException.class);
    }

    @Test
    @DisplayName("빈 파일은 S3 구현과 같이 거부한다")
    void upload_rejectsEmptyFile() {
        assertThatThrownBy(() ->
            storage.upload(image("photo.png", "image/png", 0), "profile-images"))
            .isInstanceOf(ImageUploadException.class);
    }
}
