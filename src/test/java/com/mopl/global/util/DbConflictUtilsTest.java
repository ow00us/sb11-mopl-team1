package com.mopl.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

public class DbConflictUtilsTest {

    @Test
    @DisplayName("DuplicateKeyException이면 중복키 위반으로 판별한다")
    void isDuplicateKeyViolation_true_whenDuplicateKeyException() {
        DataIntegrityViolationException e = new DuplicateKeyException("중복");

        assertThat(DbConflictUtils.isDuplicateKeyViolation(e)).isTrue();
    }

    @Test
    @DisplayName("SQLState 23505를 가진 원인 예외면 중복키 위반으로 판별한다")
    void isDuplicateKeyViolation_true_whenSqlState23505() {
        SQLException sqlException = new SQLException("중복", "23505");
        DataIntegrityViolationException e =
            new DataIntegrityViolationException("래핑됨", sqlException);

        assertThat(DbConflictUtils.isDuplicateKeyViolation(e)).isTrue();
    }

    @Test
    @DisplayName("다른 SQLState(FK 위반 등)는 중복키 위반이 아니다")
    void isDuplicateKeyViolation_false_whenOtherSqlState() {
        SQLException sqlException = new SQLException("FK 위반", "23503");
        DataIntegrityViolationException e =
            new DataIntegrityViolationException("래핑됨", sqlException);

        assertThat(DbConflictUtils.isDuplicateKeyViolation(e)).isFalse();
    }

    @Test
    @DisplayName("원인이 SQLException이 아니면 중복키 위반이 아니다")
    void isDuplicateKeyViolation_false_whenCauseIsNotSqlException() {
        DataIntegrityViolationException e =
            new DataIntegrityViolationException("원인 없음");

        assertThat(DbConflictUtils.isDuplicateKeyViolation(e)).isFalse();
    }
}
