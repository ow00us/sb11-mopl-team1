package com.mopl.content.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContentTypeTest {

    @Test
    @DisplayName("apiValue는 movie/tvSeries/sport로 매핑된다")
    void getApiValue_returnsCamelCase() {
        assertThat(ContentType.MOVIE.getApiValue()).isEqualTo("movie");
        assertThat(ContentType.TV_SERIES.getApiValue()).isEqualTo("tvSeries");
        assertThat(ContentType.SPORT.getApiValue()).isEqualTo("sport");
    }

    @Test
    @DisplayName("fromApiValue는 camelCase 문자열을 대소문자 구분 없이 enum으로 변환한다")
    void fromApiValue_parsesKnownValues() {
        assertThat(ContentType.fromApiValue("movie")).isEqualTo(ContentType.MOVIE);
        assertThat(ContentType.fromApiValue("tvseries")).isEqualTo(ContentType.TV_SERIES);
        assertThat(ContentType.fromApiValue("Sport")).isEqualTo(ContentType.SPORT);
    }

    @Test
    @DisplayName("알 수 없는 값이면 BusinessException(INVALID_INPUT)이 발생한다")
    void fromApiValue_unknownValue_throwsBusinessException() {
        assertThatThrownBy(() -> ContentType.fromApiValue("documentary"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }
}