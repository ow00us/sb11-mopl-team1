package com.mopl.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Auditing 기능을 켜 주는 설정입니다.
 * 이 설정이 있어야 BaseEntity의 createdAt·updatedAt이 저장/수정 시점에 자동으로 채워집니다.
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
