package com.mopl.content.external.sportsdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SportsDbPropertiesTest {

    @Test
    @DisplayName("baseUrl이 https로 시작하면 정상 생성된다")
    void constructor_httpsBaseUrl_succeeds() {
        SportsDbProperties properties = new SportsDbProperties(
                "https://www.thesportsdb.com/api/v1/json", "123", List.of(4569));

        assertThat(properties.baseUrl()).isEqualTo("https://www.thesportsdb.com/api/v1/json");
    }

    @Test
    @DisplayName("baseUrl이 http면 예외를 던진다")
    void constructor_httpBaseUrl_throwsException() {
        assertThatThrownBy(() -> new SportsDbProperties(
                "http://www.thesportsdb.com/api/v1/json", "123", List.of(4569)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("baseUrl이 null이면 검증을 건너뛰고 그대로 생성된다")
    void constructor_nullBaseUrl_doesNotThrow() {
        SportsDbProperties properties = new SportsDbProperties(null, "123", List.of(4569));

        assertThat(properties.baseUrl()).isNull();
    }
}