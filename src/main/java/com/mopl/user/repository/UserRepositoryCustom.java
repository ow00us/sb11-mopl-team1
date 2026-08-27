package com.mopl.user.repository;

import com.mopl.user.dto.UserListRequest;
import com.mopl.user.entity.User;
import java.util.List;
import java.util.UUID;

/**
 * 관리자 사용자 목록 조회에 필요한 동적 쿼리를 정의
 *
 * JpaRepository의 기본 CRUD로 표현하기 어려운 다음 기능을 담당
 *
 * 1. 이메일·역할·잠금 상태 동적 필터
 * 2. 다섯 가지 정렬 기준
 * 3. 오름차순·내림차순
 * 4. cursor와 idAfter를 이용한 커서 페이지네이션
 */
public interface UserRepositoryCustom {

    /**
     * 요청 조건에 맞는 사용자 한 페이지를 조회
     *
     * 실제 요청 limit보다 한 건 더 조회하여 Service가
     * 다음 페이지 존재 여부를 판단할 수 있도록 한다.
     *
     * @param request 사용자 검색·필터·커서 조건
     * @return 최대 limit + 1개의 사용자 목록
     */
    List<User> findUsers(UserListRequest request);

    /**
     * 커서와 페이지 크기를 제외한 필터 조건에 해당하는
     * 전체 사용자 수를 조회
     *
     * @param request 사용자 검색·필터 조건
     * @return 필터에 해당하는 전체 사용자 수
     */
    long countUsers(UserListRequest request);

    List<User> searchUsersByName(
        UUID requesterId,
        String keyword,
        String cursorName,
        UUID idAfter,
        int limit
    );

    long countSearchUsersByName(
        UUID requesterId,
        String keyword
    );
}
