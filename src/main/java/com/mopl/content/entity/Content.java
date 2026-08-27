package com.mopl.content.entity;

import com.mopl.global.common.BaseEntity;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Getter
@Table(name = "contents")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLRestriction("deleted_at IS NULL")
@SQLDelete(sql = "UPDATE contents SET deleted_at = now(), updated_at = now() WHERE id = ?")
// V6 마이그레이션이 ck_contents_source 제약을 NOT VALID로 추가해서, enum에 없는 레거시 source 값이
// 검증 없이 남아있을 수 있다. ContentSourceConverter는 그런 값을 읽을 때 예외 대신 null로 매핑하는데,
// @DynamicUpdate가 없으면 Hibernate가 UPDATE 시 변경 안 된 컬럼까지 전부 다시 써서, title/description만
// 고치는 Content.update() 호출에도 메모리상 null이 된 source가 DB에 그대로 덮어써져 레거시 값이 사라지고
// external_id가 있는 행이면 ck_contents_external_source 제약까지 위반할 수 있다.
// @DynamicUpdate로 실제로 변경된 컬럼만 UPDATE하게 해서 이 문제를 막는다.
@DynamicUpdate
public class Content extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ContentType type;

    @Convert(converter = ContentSourceConverter.class)
    @Column(length = 50)
    private ContentSource source;

    @Column(name = "external_id")
    private String externalId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "thumbnail_url", length = 2048)
    private String thumbnailUrl;

    @Column(name = "average_rating", nullable = false, precision = 2, scale = 1)
    private BigDecimal averageRating = BigDecimal.valueOf(0.0).setScale(1);

    @Column(name = "review_count", nullable = false)
    private Long reviewCount = 0L;

    @Column(name = "watcher_count", nullable = false)
    private Long watcherCount = 0L;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @ElementCollection
    @CollectionTable(name = "content_tags", joinColumns = @JoinColumn(name = "content_id"))
    @MapKeyColumn(name = "tag", length = 100)
    @Column(name = "display_tag", length = 100, nullable = false)
    @BatchSize(size = 100)
    @Getter(AccessLevel.NONE)
    private Map<String, String> tags = new HashMap<>();

    @Builder
    public Content(ContentType type, ContentSource source, String externalId, String title,
                   String description, String thumbnailUrl) {
        if (externalId != null && source == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "externalId가 있으면 source도 함께 지정해야 합니다.");
        }
        this.type = type;
        this.source = source;
        this.externalId = externalId;
        this.title = title;
        this.description = description;
        this.thumbnailUrl = thumbnailUrl;
    }

    public Set<String> getTags() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(tags.values()));
    }

    public Set<String> getNormalizedTags() {
        return Collections.unmodifiableSet(tags.keySet());
    }

    public void update(String title, String description, Set<String> tags) {
        if (title != null) {
            this.title = title;
        }
        if (description != null) {
            this.description = description;
        }
        if (tags != null) {
            Map<String, String> normalizedTags = new HashMap<>();
            for (String rawTag : tags) {
                normalizedTags.put(normalize(rawTag), toDisplay(rawTag));
            }
            this.tags.clear();
            this.tags.putAll(normalizedTags);
        }
    }

    public void updateThumbnail(String thumbnailUrl) {
        this.thumbnailUrl = thumbnailUrl;
    }

    public boolean addTag(String rawTag) {
        String normalized = normalize(rawTag);
        String display = toDisplay(rawTag);
        boolean isNew = !this.tags.containsKey(normalized);
        this.tags.put(normalized, display);
        return isNew;
    }

    public static String normalize(String rawTag) {
        return sanitize(rawTag).toLowerCase(Locale.ROOT);
    }

    private static String toDisplay(String rawTag) {
        return sanitize(rawTag);
    }

    private static String sanitize(String rawTag) {
        if (rawTag == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "태그는 비어 있을 수 없습니다.");
        }
        String sanitized = rawTag.strip().replaceAll("\\s+", " ");
        if (sanitized.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "태그는 비어 있을 수 없습니다.");
        }
        if (sanitized.length() > 100) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "태그는 100자를 초과할 수 없습니다.");
        }
        return sanitized;
    }
}
