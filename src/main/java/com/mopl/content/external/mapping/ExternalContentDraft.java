package com.mopl.content.external.mapping;

import com.mopl.content.entity.ContentSource;
import com.mopl.content.entity.ContentType;
import java.util.Set;

public record ExternalContentDraft(
        ContentType type,
        ContentSource source,
        String externalId,
        String title,
        String description,
        String thumbnailUrl,
        Set<String> tags
) {
}