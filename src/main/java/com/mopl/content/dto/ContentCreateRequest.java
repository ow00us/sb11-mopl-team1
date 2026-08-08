package com.mopl.content.dto;

import com.mopl.content.entity.ContentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ContentCreateRequest(
        @NotNull ContentType type,
        @NotBlank @Size(max = 255) String title,
        @NotBlank String description,
        @NotNull List<String> tags
) {}