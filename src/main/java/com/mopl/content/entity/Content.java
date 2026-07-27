package com.mopl.content.entity;

import com.mopl.global.common.BaseEntity;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Getter
@Table(name = "contents")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLRestriction("deleted_at IS NULL")
public class Content extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ContentType type;

    @Enumerated(EnumType.STRING)
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
    @Column(name = "tag", length = 100, nullable = false)
    @Getter(AccessLevel.NONE)
    private Set<String> tags = new HashSet<>();

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
        return Collections.unmodifiableSet(tags);
    }

    public boolean addTag(String rawTag) {
        String normalized = normalize(rawTag);
        return this.tags.add(normalized);
    }

    private String normalize(String rawTag) {
        if (rawTag == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "태그는 비어 있을 수 없습니다.");
        }
        String normalized = rawTag.strip().replaceAll("\\s+", " ").toLowerCase();
        if (normalized.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "태그는 비어 있을 수 없습니다.");
        }
        return normalized;
    }
}
