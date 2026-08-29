package com.mopl.global.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.global.exception.ErrorCode;
import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * 업로드 검증과 객체 키 생성을 검증합니다.
 *
 * <p>실제 AWS 에 접속하지 않습니다. 확인하려는 것은 무엇을 거부하고 어떤 키로 올리는지이고,
 * 그것은 클라이언트에 무엇을 넘겼는지로 드러납니다.
 */
class S3ImageObjectStorageTest {

    private static final ImageStorageProperties PROPERTIES = new ImageStorageProperties(
        true,
        "mopl-images",
        "ap-northeast-2",
        "https://cdn.mopl.example.com",
        "profile-images",
        "thumbnails",
        5_242_880L,
        Set.of("image/jpeg", "image/png", "image/webp", "image/gif"));

    private final S3Client s3Client = mock(S3Client.class);
    private final ImageObjectStorage storage = new S3ImageObjectStorage(s3Client, PROPERTIES);

    private static MultipartFile image(String filename, String contentType, int size) {
        return new MockMultipartFile("file", filename, contentType, new byte[size]);
    }

    private PutObjectRequest capturedRequest() {
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        return captor.getValue();
    }

    @Test
    @DisplayName("업로드하면 조회 URL을 돌려준다")
    void upload_returnsPublicUrl() {
        String url = storage.upload(image("photo.png", "image/png", 100), "profile-images");

        assertThat(url).startsWith("https://cdn.mopl.example.com/profile-images/");
        assertThat(url).endsWith(".png");
    }

    @Test
    @DisplayName("버킷과 Content-Type을 그대로 넘긴다")
    void upload_sendsBucketAndContentType() {
        storage.upload(image("photo.png", "image/png", 100), "profile-images");

        PutObjectRequest request = capturedRequest();
        assertThat(request.bucket()).isEqualTo("mopl-images");
        assertThat(request.contentType()).isEqualTo("image/png");
        assertThat(request.contentLength()).isEqualTo(100);
    }

    /**
     * 파일명을 키로 쓰면 두 사용자가 같은 이름을 올렸을 때 뒤가 앞을 덮습니다.
     */
    @Test
    @DisplayName("원본 파일명을 객체 키에 쓰지 않는다")
    void upload_doesNotUseOriginalFilename() {
        storage.upload(image("내-사진.png", "image/png", 100), "profile-images");

        assertThat(capturedRequest().key()).doesNotContain("내-사진");
    }

    @Test
    @DisplayName("같은 파일을 두 번 올려도 키가 겹치지 않는다")
    void upload_generatesDistinctKeys() {
        storage.upload(image("photo.png", "image/png", 100), "profile-images");
        storage.upload(image("photo.png", "image/png", 100), "profile-images");

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, org.mockito.Mockito.times(2))
            .putObject(captor.capture(), any(RequestBody.class));

        assertThat(captor.getAllValues().get(0).key())
            .isNotEqualTo(captor.getAllValues().get(1).key());
    }

    /**
     * 파일명은 사용자가 정하는 값입니다. 경로 기호가 섞이면 의도한 구분자 밖으로 나갑니다.
     */
    @Test
    @DisplayName("파일명에 경로 기호가 있어도 구분자 밖으로 나가지 않는다")
    void upload_rejectsPathTraversalInFilename() {
        storage.upload(image("../../etc/passwd.png", "image/png", 100), "profile-images");

        String key = capturedRequest().key();
        assertThat(key).startsWith("profile-images/");
        assertThat(key).doesNotContain("..");
    }

    /**
     * 확장자를 파일명이 아니라 Content-Type 에서 얻습니다. 파일명의 확장자는 내용과 무관하게
     * 붙일 수 있어 검증한 형식과 저장한 이름이 어긋날 수 있습니다.
     */
    @Test
    @DisplayName("확장자를 Content-Type에서 정한다")
    void upload_derivesExtensionFromContentType() {
        storage.upload(image("photo.txt", "image/jpeg", 100), "thumbnails");

        assertThat(capturedRequest().key()).endsWith(".jpg");
    }

    @Test
    @DisplayName("허용하지 않는 형식은 올리지 않는다")
    void upload_rejectsDisallowedContentType() {
        assertThatThrownBy(() ->
            storage.upload(image("script.svg", "image/svg+xml", 100), "profile-images"))
            .isInstanceOf(ImageUploadException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IMAGE_UPLOAD_REJECTED);

        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("크기 상한을 넘으면 올리지 않는다")
    void upload_rejectsOversizedFile() {
        assertThatThrownBy(() ->
            storage.upload(image("photo.png", "image/png", 5_242_881), "profile-images"))
            .isInstanceOf(ImageUploadException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IMAGE_UPLOAD_REJECTED);

        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("빈 파일은 올리지 않는다")
    void upload_rejectsEmptyFile() {
        assertThatThrownBy(() ->
            storage.upload(image("photo.png", "image/png", 0), "profile-images"))
            .isInstanceOf(ImageUploadException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IMAGE_UPLOAD_REJECTED);

        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("null 파일은 외부 저장소를 호출하기 전에 거부한다")
    void upload_rejectsNullFile() {
        assertThatThrownBy(() -> storage.upload(null, "profile-images"))
            .isInstanceOf(ImageUploadException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IMAGE_UPLOAD_REJECTED);

        verifyNoInteractions(s3Client);
    }

    @Test
    @DisplayName("Content-Type이 없으면 외부 저장소를 호출하지 않는다")
    void upload_rejectsMissingContentType() {
        assertThatThrownBy(() ->
            storage.upload(image("photo.png", null, 10), "profile-images"))
            .isInstanceOf(ImageUploadException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IMAGE_UPLOAD_REJECTED);

        verifyNoInteractions(s3Client);
    }

    @Test
    @DisplayName("크기가 상한과 정확히 같으면 업로드를 허용한다")
    void upload_acceptsFileAtSizeLimit() {
        String url = storage.upload(
            image("photo.png", "image/png", (int) PROPERTIES.maxFileSize()), "profile-images");

        assertThat(url).startsWith(PROPERTIES.publicBaseUrl() + "/profile-images/");
        assertThat(capturedRequest().contentLength()).isEqualTo(PROPERTIES.maxFileSize());
    }

    @Test
    @DisplayName("허용 목록에 있어도 확장자 매핑이 없는 MIME이면 업로드하지 않는다")
    void upload_rejectsAllowedMimeWithoutKeyMapping() {
        ImageStorageProperties unmapped = new ImageStorageProperties(
            true, "mopl-images", "ap-northeast-2", PROPERTIES.publicBaseUrl(),
            "profile-images", "thumbnails", PROPERTIES.maxFileSize(), Set.of("image/svg+xml"));

        assertThatThrownBy(() -> new S3ImageObjectStorage(s3Client, unmapped)
            .upload(image("photo.svg", "image/svg+xml", 10), "profile-images"))
            .isInstanceOf(ImageUploadException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IMAGE_UPLOAD_REJECTED);

        verifyNoInteractions(s3Client);
    }

    @Test
    @DisplayName("파일 스트림을 읽지 못하면 저장 실패로 변환하고 S3를 호출하지 않는다")
    void upload_convertsInputStreamFailure() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(10L);
        when(file.getContentType()).thenReturn("image/png");
        when(file.getInputStream()).thenThrow(new IOException("test stream unavailable"));

        assertThatThrownBy(() -> storage.upload(file, "profile-images"))
            .isInstanceOf(ImageUploadException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IMAGE_UPLOAD_FAILED);

        verifyNoInteractions(s3Client);
    }

    /**
     * 올리지 못했으면 URL 이 없습니다. 조용히 넘기면 이미지 없는 레코드가 저장되고 사용자에게는
     * 성공으로 보입니다.
     */
    @Test
    @DisplayName("업로드가 실패하면 예외로 끊는다")
    void upload_failsLoudlyWhenS3Rejects() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
            .thenThrow(S3Exception.builder().message("접근 거부").build());

        assertThatThrownBy(() ->
            storage.upload(image("photo.png", "image/png", 100), "profile-images"))
            .isInstanceOf(ImageUploadException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IMAGE_UPLOAD_FAILED);
    }

    @Test
    @DisplayName("조회 URL 앞부분에 구분 기호가 겹치지 않는다")
    void upload_joinsPublicBaseUrlWithoutDoubleSlash() {
        ImageStorageProperties trailing = new ImageStorageProperties(
            true, "mopl-images", "ap-northeast-2", "https://cdn.mopl.example.com/",
            "profile-images", "thumbnails", 5_242_880L, Set.of("image/png"));

        String url = new S3ImageObjectStorage(s3Client, trailing)
            .upload(image("photo.png", "image/png", 100), "profile-images");

        assertThat(url).doesNotContain("com//");
    }
}
