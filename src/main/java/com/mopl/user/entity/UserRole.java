package com.mopl.user.entity;

/**
 * 사용자가 시스템에서 가질 수 있는 권한을 정의합니다.
 *
 * 데이터베이스에는 다음 CHECK 제약조건이 있으므로, enum 상수 이름과 DB에 허용된 문자열이 정확히 일치해야 합니다.
 */
public enum UserRole {

    USER,
    ADMIN
}
