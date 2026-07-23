package com.mopl.global.common;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * 모든 엔티티가 공통으로 갖는 id(UUID)·생성시각·수정시각을 담아 두는 부모 클래스입니다.
 * 각 도메인 엔티티는 이 클래스를 상속하기만 하면 세 필드를 그대로 물려받습니다.
 * 생성/수정 시각은 JPA Auditing이 저장·수정 시점에 자동으로 채워 줍니다(JpaConfig 참고).
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @CreatedDate
    @Column(updatable = false, nullable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;
}
