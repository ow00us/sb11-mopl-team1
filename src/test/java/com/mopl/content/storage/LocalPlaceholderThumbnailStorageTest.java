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
    @DisplayName("파일명이 있으면 placeholder URL에 원본 파일명이 포함된다")
    void upload_withFilename_includesOriginalFilename() {
        MultipartFile file = new MockMultipartFile("thumbnail", "poster.png", "image/png", new byte[]{1});

        String url = storage.upload(file);

        assertThat(url).startsWith("https://placeholder.mopl.local/thumbnails/");
        assertThat(url).endsWith("-poster.png");
    }

    @Test
    @DisplayName("파일명이 없으면 기본값 thumbnail을 사용한다")
    void upload_withoutFilename_usesDefaultName() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn(null);

        String url = storage.upload(file);

        assertThat(url).endsWith("-thumbnail");
    }
}