package com.mopl.sample.entity;

import com.mopl.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 도메인 슬라이스를 어떻게 짜면 되는지 보여 주는 예시 엔티티입니다.
 * BaseEntity를 상속하고, Lombok은 @Getter·@Builder만 쓰며 @Setter는 쓰지 않는(불변을 지향하는) 패턴을 보여 줍니다.
 * 실제 도메인 개발을 시작하면 이 sample 패키지는 지우거나 여러분의 도메인으로 바꾸시면 됩니다.
 */
@Entity
@Getter
@Table(name = "sample")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Sample extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Builder
    public Sample(String name) {
        this.name = name;
    }
}
