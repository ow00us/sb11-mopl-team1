package com.mopl.playlist.dto;

import jakarta.validation.constraints.Size;

public record PlaylistUpdateRequest(
        @Size(max = 200) String title,
        String description
) {}