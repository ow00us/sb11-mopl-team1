package com.mopl.content.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import java.util.Arrays;

public enum ContentType {
    MOVIE("movie"),
    TV_SERIES("tvSeries"),
    SPORT("sport");

    private final String apiValue;

    ContentType(String apiValue) {
        this.apiValue = apiValue;
    }

    @JsonValue
    public String getApiValue() {
        return apiValue;
    }

    @JsonCreator
    public static ContentType fromApiValue(String apiValue) {
        return Arrays.stream(values())
                .filter(type -> type.apiValue.equalsIgnoreCase(apiValue))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INVALID_INPUT, "알 수 없는 콘텐츠 타입입니다: " + apiValue));
    }
}