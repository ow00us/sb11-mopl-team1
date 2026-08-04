package com.mopl.user.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * 프로필 이미지 임시 저장소가 생성하는 URL 형식을 검증
 *
 * 실제 파일 시스템이나 S3를 사용하지 않기 때문에
 * 외부 환경 없이 빠르게 실행할 수 있는 단위 테스트
 */
class LocalPlaceholderProfileImageStorageTest {

    /**
     * Spring Bean을 실행하지 않고 테스트 대상 구현체를 직접 생성
     */
    private final LocalPlaceholderProfileImageStorage profileImageStorage =
        new LocalPlaceholderProfileImageStorage();

    @Test
    @DisplayName("프로필 이미지 업로드 시 UUID와 기존 확장자를 포함한 URL을 반환한다")
    void upload_success_whenSafeExtensionExists() {
        // given
        MockMultipartFile image = new MockMultipartFile(
            "image",
            "profile.png",
            "image/png",
            new byte[]{1, 2, 3}
        );

        // when
        String imageUrl = profileImageStorage.upload(image);

        // then
        /*
         * 파일명은 실행할 때마다 임의의 UUID로 생성되므로
         * 정확한 전체 문자열 대신 URL 형식을 검증
         */
        assertThat(imageUrl)
            .matches(
                "https://placeholder\\.mopl\\.local/profile-images/"
                    + "[0-9a-f\\-]{36}\\.png"
            );
    }

    @Test
    @DisplayName("안전하지 않은 확장자는 URL에 포함하지 않는다")
    void upload_success_withoutUnsafeExtension() {
        // given
        MockMultipartFile image = new MockMultipartFile(
            "image",
            "profile.verylongextension",
            "application/octet-stream",
            new byte[]{1, 2, 3}
        );

        // when
        String imageUrl = profileImageStorage.upload(image);

        // then
        /*
         * 허용 길이인 1~10자를 초과하는 확장자는 제거되고
         * URL은 UUID로 끝나야 함.
         */
        assertThat(imageUrl)
            .matches(
                "https://placeholder\\.mopl\\.local/profile-images/"
                    + "[0-9a-f\\-]{36}"
            );
    }

    @Test
    @DisplayName("원본 파일명이 없어도 확장자 없는 URL을 반환한다")
    void upload_success_whenOriginalFilenameDoesNotExist() {
        // given
        MockMultipartFile image = new MockMultipartFile(
            "image",
            null,
            "image/png",
            new byte[]{1, 2, 3}
        );

        // when
        String imageUrl = profileImageStorage.upload(image);

        // then
        assertThat(imageUrl)
            .matches(
                "https://placeholder\\.mopl\\.local/profile-images/"
                    + "[0-9a-f\\-]{36}"
            );
    }

    @Test
    @DisplayName("같은 파일을 여러 번 업로드해도 서로 다른 URL을 반환한다")
    void upload_success_withUniqueUrl() {
        // given
        MockMultipartFile image = new MockMultipartFile(
            "image",
            "profile.png",
            "image/png",
            new byte[]{1, 2, 3}
        );

        // when
        String firstImageUrl =
            profileImageStorage.upload(image);

        String secondImageUrl =
            profileImageStorage.upload(image);

        // then
        /*
         * 원본 파일명이 같더라도 UUID가 매번 새로 생성되어
         * 기존 이미지 URL과 충돌하지 않아야 함.
         */
        assertThat(firstImageUrl)
            .isNotEqualTo(secondImageUrl);
    }
}
