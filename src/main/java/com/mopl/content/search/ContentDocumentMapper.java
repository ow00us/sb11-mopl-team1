package com.mopl.content.search;

import com.mopl.content.entity.Content;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import org.springframework.stereotype.Component;

@Component
public class ContentDocumentMapper {

    public ContentDocument toDocument(Content content, ContentDocument existing) {
        return ContentDocument.builder()
                .id(content.getId().toString())
                .title(content.getTitle())
                .description(content.getDescription())
                .type(content.getType().name())
                .tags(new ArrayList<>(content.getTags()))
                .averageRating(content.getAverageRating().doubleValue())
                .createdAt(LocalDateTime.ofInstant(content.getCreatedAt(), ZoneId.systemDefault()))
                .watcherCount(existing != null ? existing.getWatcherCount() : 0)
                .build();
    }
}