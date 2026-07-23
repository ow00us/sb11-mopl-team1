package com.mopl.sample.dto;

import com.mopl.sample.entity.Sample;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO를 어떻게 만드는지 보여 주는 예시입니다. record로 정의하고, 엔티티→DTO 변환은 정적 from()으로 합니다.
 * 엔티티를 컨트롤러에서 그대로 노출하지 않고 항상 DTO로 감싸 주세요.
 */
public record SampleDto(
        UUID id,
        String name,
        Instant createdAt
) {
    public static SampleDto from(Sample sample) {
        return new SampleDto(sample.getId(), sample.getName(), sample.getCreatedAt());
    }
}
