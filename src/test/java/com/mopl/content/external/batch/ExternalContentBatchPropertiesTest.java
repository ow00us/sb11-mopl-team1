package com.mopl.content.external.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExternalContentBatchPropertiesTest {

    @Test
    @DisplayName("모든 값이 유효 범위면 정상 생성된다")
    void constructor_validValues_succeeds() {
        ExternalContentBatchProperties properties = new ExternalContentBatchProperties(5, 3, 3, "Asia/Seoul");

        assertThat(properties.tmdbMaxPages()).isEqualTo(5);
        assertThat(properties.sportsDbPastDays()).isEqualTo(3);
        assertThat(properties.sportsDbFutureDays()).isEqualTo(3);
    }

    @Test
    @DisplayName("tmdbMaxPages가 0 이하면 예외를 던진다")
    void constructor_nonPositiveTmdbMaxPages_throwsException() {
        assertThatThrownBy(() -> new ExternalContentBatchProperties(0, 3, 3, "Asia/Seoul"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("sportsDbPastDays가 음수면 예외를 던진다")
    void constructor_negativeSportsDbPastDays_throwsException() {
        assertThatThrownBy(() -> new ExternalContentBatchProperties(5, -1, 3, "Asia/Seoul"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("sportsDbFutureDays가 음수면 예외를 던진다")
    void constructor_negativeSportsDbFutureDays_throwsException() {
        assertThatThrownBy(() -> new ExternalContentBatchProperties(5, 3, -1, "Asia/Seoul"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("zone이 공백이면 예외를 던진다")
    void constructor_blankZone_throwsException() {
        assertThatThrownBy(() -> new ExternalContentBatchProperties(5, 3, 3, "  "))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("zone이 유효하지 않은 값이면 예외를 던진다")
    void constructor_invalidZone_throwsException() {
        assertThatThrownBy(() -> new ExternalContentBatchProperties(5, 3, 3, "Not/AZone"))
                .isInstanceOf(IllegalStateException.class);
    }
}
