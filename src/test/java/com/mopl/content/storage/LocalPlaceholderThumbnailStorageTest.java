package com.mopl.content.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

class LocalPlaceholderThumbnailStorageTest {

    private final LocalPlaceholderThumbnailStorage storage = new LocalPlaceholderThumbnailStorage();

    @Test
    @DisplayName("파일명에 안전한 확장자가 있으면 URL 끝에 그 확장자만 붙는다")
    void upload_withSafeExtension_appendsOnlyExtension() {
        MultipartFile file = new MockMultipartFile("thumbnail", "poster.png", "image/png", new byte[]{1});

        String url = storage.upload(file);

        assertThat(url).startsWith("https://placeholder.mopl.local/thumbnails/");
        assertThat(url).endsWith(".png");
        assertThat(url).doesNotContain("poster");
    }

    @Test
    @DisplayName("파일명이 없으면 확장자 없이 UUID만 사용한다")
    void upload_withoutFilename_usesUuidOnly() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn(null);

        String url = storage.upload(file);

        assertThat(url).matches("https://placeholder\\.mopl\\.local/thumbnails/[0-9a-fA-F-]{36}");
    }

    @Test
    @DisplayName("파일명에 경로 구분자·쿼리 문자가 섞여 있어도 안전한 확장자만 추출되고 나머지는 URL에 노출되지 않는다")
    void upload_withMaliciousFilename_extractsOnlySafeExtension() {
        MultipartFile file = new MockMultipartFile(
                "thumbnail", "../../etc/passwd?x=1#frag.png", "image/png", new byte[]{1});

        String url = storage.upload(file);

        assertThat(url).endsWith(".png");
        assertThat(url).doesNotContain("etc", "passwd", "?", "#", "..");
    }
}