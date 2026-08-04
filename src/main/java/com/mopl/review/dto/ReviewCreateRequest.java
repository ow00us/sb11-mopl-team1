package com.mopl.review.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record ReviewCreateRequest(
        @NotNull UUID contentId,
        @NotBlank String text,
        @NotNull
        @DecimalMin(value = "0.0")
        @DecimalMax(value = "5.0")
        @Schema(multipleOf = 0.5)
        BigDecimal rating
) {}