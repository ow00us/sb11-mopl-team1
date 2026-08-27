package com.mopl.content.search;

import com.mopl.content.entity.Content;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ContentDocumentMapper {

    // ContentDocument.createdAt 매핑의 DateFormat.date_hour_minute_second_millis 포맷과 동일하다.
    // 부분 업데이트(Document.from(Map))는 엔티티 매핑을 거치지 않고 원시 Jackson으로 직렬화되는데,
    // 그 Jackson에는 JSR-310 모듈이 없어 LocalDateTime을 그대로 넣으면 직렬화 자체가 실패한다.
    // 그래서 이 포맷 문자열로 미리 변환해 넣는다.
    private static final DateTimeFormatter CREATED_AT_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS");

    // 새 문서를 처음 색인할 때만 쓴다. watcherCount는 실제 값을 알 수 없으니 0으로 시작하고,
    // 이후 WatcherCountRefreshService의 부분 업데이트가 채운다.
    public ContentDocument toNewDocument(Content content) {
        return ContentDocument.builder()
                .id(content.getId().toString())
                .contentId(content.getId().toString())
                .title(content.getTitle())
                .description(content.getDescription())
                .type(content.getType().name())
                .tags(new ArrayList<>(content.getNormalizedTags()))
                .displayTags(new ArrayList<>(content.getTags()))
                .averageRating(content.getAverageRating().doubleValue())
                .reviewCount(content.getReviewCount().intValue())
                .thumbnailUrl(content.getThumbnailUrl())
                .createdAt(LocalDateTime.ofInstant(content.getCreatedAt(), ZoneOffset.UTC))
                .createdAtEpochMicros(toEpochMicros(content.getCreatedAt()))
                .watcherCount(0)
                .build();
    }

    // 이미 색인된 문서를 갱신할 때 쓴다. watcherCount 필드는 절대 포함하지 않는다 —
    // 포함하면 WatcherCountRefreshService가 반영한 값을 덮어쓸 수 있다.
    // contentId는 불변값이라 매번 같은 값을 다시 써넣는 셈이지만, 여기서 빼면 옛날에 색인된
    // 문서(contentId 필드가 아예 없던 시절)가 이후 아무리 갱신돼도 영원히 이 필드를 못 받는다.
    // search_after tie-breaker로 모든 정렬에 쓰이는 값이라 누락되면 커서 페이지네이션이 깨질 수
    // 있으므로, 매번 다시 써넣는 비용을 감수하고 포함시킨다.
    public Map<String, Object> toUpdateFields(Content content) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("contentId", content.getId().toString());
        fields.put("title", content.getTitle());
        fields.put("description", content.getDescription());
        fields.put("type", content.getType().name());
        fields.put("tags", new ArrayList<>(content.getNormalizedTags()));
        fields.put("displayTags", new ArrayList<>(content.getTags()));
        fields.put("averageRating", content.getAverageRating().doubleValue());
        fields.put("reviewCount", content.getReviewCount().intValue());
        fields.put("thumbnailUrl", content.getThumbnailUrl());
        LocalDateTime createdAt = LocalDateTime.ofInstant(content.getCreatedAt(), ZoneOffset.UTC);
        fields.put("createdAt", CREATED_AT_FORMAT.format(createdAt));
        fields.put("createdAtEpochMicros", toEpochMicros(content.getCreatedAt()));
        return fields;
    }

    private long toEpochMicros(Instant instant) {
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000;
    }
}
