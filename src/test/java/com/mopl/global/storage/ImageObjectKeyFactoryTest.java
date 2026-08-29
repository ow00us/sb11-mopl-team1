package com.mopl.global.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mopl.global.exception.ErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ImageObjectKeyFactoryTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t", "/", "////", "  ///  "})
    @DisplayName("비어 있거나 슬래시뿐인 prefix는 루트 객체 키가 된다")
    void emptyPrefixesDoNotLeaveASeparator(String prefix) {
        String key = ImageObjectKeyFactory.create(prefix, "image/png");

        assertThat(key).doesNotContain("/").endsWith(".png");
        assertThat(UUID.fromString(key.substring(0, key.length() - 4))).isNotNull();
    }

    @ParameterizedTest
    @CsvSource(value = {
        "profiles|profiles/",
        "/profiles/|profiles/",
        "///profiles///|profiles/",
        "  /tenant/images///  |tenant/images/"
    }, delimiter = '|', ignoreLeadingAndTrailingWhitespace = false)
    @DisplayName("prefix 앞뒤 공백과 슬래시는 정리하고 내부 경로는 보존한다")
    void normalizesOnlyOuterPrefixSeparators(String prefix, String expectedPrefix) {
        String key = ImageObjectKeyFactory.create(prefix, "image/jpeg");

        assertThat(key).startsWith(expectedPrefix).doesNotContain("//").endsWith(".jpg");
        String identifier = key.substring(expectedPrefix.length(), key.length() - 4);
        assertThat(UUID.fromString(identifier)).isNotNull();
    }

    @ParameterizedTest
    @CsvSource({"image/jpeg,.jpg", "image/png,.png", "image/webp,.webp", "image/gif,.gif"})
    @DisplayName("지원 MIME마다 검증된 확장자를 사용한다")
    void derivesExtensionFromSupportedMimeType(String contentType, String extension) {
        assertThat(ImageObjectKeyFactory.create("images", contentType)).endsWith(extension);
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/svg+xml", "application/octet-stream"})
    @DisplayName("확장자 매핑이 없는 MIME은 객체 키를 만들지 않는다")
    void rejectsMimeTypesWithoutExtensionMapping(String contentType) {
        assertThatThrownBy(() -> ImageObjectKeyFactory.create("images", contentType))
            .isInstanceOf(ImageUploadException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.IMAGE_UPLOAD_REJECTED);
    }

    @Test
    @DisplayName("같은 prefix와 MIME으로 연속 생성해도 덮어쓸 키가 생기지 않는다")
    void repeatedCreationProducesDifferentKeys() {
        String first = ImageObjectKeyFactory.create("images", "image/png");

        assertThat(ImageObjectKeyFactory.create("images", "image/png")).isNotEqualTo(first);
    }
}
