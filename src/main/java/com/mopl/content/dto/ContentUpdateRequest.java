package com.mopl.content.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ContentUpdateRequest(
        @Size(max = 255) @Pattern(regexp = "(?s).*\\S.*") String title,
        @Pattern(regexp = "(?s).*\\S.*") String description,
        List<String> tags
) {}