package com.mopl.content.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ContentSourceConverterTest {

    private final ContentSourceConverter converter = new ContentSourceConverter();

    @ParameterizedTest
    @DisplayName("convertToDatabaseColumn은 enum을 이름 문자열로 변환한다")
    @EnumSource(ContentSource.class)
    void convertToDatabaseColumn_returnsEnumName(ContentSource source) {
        assertThat(converter.convertToDatabaseColumn(source)).isEqualTo(source.name());
    }

    @ParameterizedTest
    @DisplayName("convertToEntityAttribute는 알려진 이름 문자열을 해당 enum으로 변환한다")
    @EnumSource(ContentSource.class)
    void convertToEntityAttribute_knownValue_returnsEnum(ContentSource source) {
        assertThat(converter.convertToEntityAttribute(source.name())).isEqualTo(source);
    }

    @Test
    @DisplayName("convertToEntityAttribute는 enum에 없는 문자열을 예외 없이 null로 변환한다")
    void convertToEntityAttribute_unknownValue_returnsNullWithoutException() {
        assertThatCode(() -> assertThat(converter.convertToEntityAttribute("QA_SEED")).isNull())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("null 입력은 양방향 모두 null을 반환한다")
    void convert_nullInput_returnsNullBothWays() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}