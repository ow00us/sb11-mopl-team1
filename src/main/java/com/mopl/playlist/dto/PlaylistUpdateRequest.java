package com.mopl.playlist.dto;

import jakarta.validation.constraints.Size;

public record PlaylistUpdateRequest(
        @Size(max = 255) String title,
        String description
) {}