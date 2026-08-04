package com.mopl.review.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

public record ReviewUpdateRequest(
        @Pattern(regexp = "(?s).*\\S.*") String text,
        @DecimalMin(value = "0.0")
        @DecimalMax(value = "5.0")
        @Schema(multipleOf = 0.5)
        BigDecimal rating
) {}