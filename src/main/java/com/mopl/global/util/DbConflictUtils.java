// src/main/java/com/mopl/global/util/DbConflictUtils.java
package com.mopl.global.util;

import java.sql.SQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/** DB 무결성 위반 예외를 분류하는 유틸리티입니다. */
public final class DbConflictUtils {

    private static final String PG_UNIQUE_VIOLATION_SQLSTATE = "23505";

    private DbConflictUtils() {}

    /** 유니크 제약 위반(중복키)인지 판별합니다. FK 위반 등 다른 무결성 위반은 false입니다. */
    public static boolean isDuplicateKeyViolation(DataIntegrityViolationException e) {
        if (e instanceof DuplicateKeyException) {
            return true;
        }
        Throwable cause = e.getMostSpecificCause();
        return cause instanceof SQLException sqlException
            && PG_UNIQUE_VIOLATION_SQLSTATE.equals(sqlException.getSQLState());
    }
}
