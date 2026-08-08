package com.mopl.user.repository;

import com.mopl.global.util.CursorUtils;
import com.mopl.user.dto.UserListRequest;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 관리자 사용자 목록 조회용 동적 쿼리 구현체
 *
 * 정렬 기준이 name, email, createdAt, locked, role이고
 * 각 기준마다 오름차순과 내림차순을 지원해야 한다.
 *
 * 이를 정적 쿼리로 작성하면 유사한 쿼리가 반복되므로
 * JPA Criteria API를 사용하여 요청 조건에 따라 쿼리를 구성
 */
@Repository
@RequiredArgsConstructor
public class UserRepositoryCustomImpl implements UserRepositoryCustom {

    private static final String SORT_NAME = "name";
    private static final String SORT_EMAIL = "email";
    private static final String SORT_CREATED_AT = "createdAt";
    private static final String SORT_LOCKED = "locked";
    private static final String SORT_ROLE = "role";

    private static final String DIRECTION_ASCENDING = "ASCENDING";

    private final EntityManager entityManager;

    /**
     * 필터, 커서 및 정렬 조건을 적용하여 사용자 한 페이지를 조회
     *
     * 요청 limit보다 한 건 더 조회하는 이유는 별도의 추가 조회 없이
     * 다음 페이지가 존재하는지 판단하기 위함.
     */
    @Override
    public List<User> findUsers(UserListRequest request) {
        validateCursorPair(request);

        CriteriaBuilder criteriaBuilder =
            entityManager.getCriteriaBuilder();

        CriteriaQuery<User> criteriaQuery =
            criteriaBuilder.createQuery(User.class);

        Root<User> user = criteriaQuery.from(User.class);

        List<Predicate> predicates =
            buildFilterPredicates(
                criteriaBuilder,
                user,
                request
            );

        /*
         * 첫 페이지는 cursor와 idAfter가 모두 null이므로
         * 필터 조건만 적용
         *
         * 다음 페이지에서는 마지막으로 조회한 정렬 값과 UUID보다
         * 뒤에 위치한 데이터만 조회하도록 커서 조건을 추가
         */
        if (request.cursor() != null) {
            predicates.add(
                buildCursorPredicate(
                    criteriaBuilder,
                    user,
                    request
                )
            );
        }

        criteriaQuery
            .select(user)
            .where(predicates.toArray(Predicate[]::new))
            .orderBy(
                isAscending(request)
                    ? criteriaBuilder.asc(user.get(request.sortBy()))
                    : criteriaBuilder.desc(user.get(request.sortBy())),

                /*
                 * 주 정렬 값이 같은 사용자를 안정적으로 정렬하기 위한
                 * 보조 정렬 기준
                 *
                 * 주 정렬 방향과 관계없이 UUID는 오름차순으로 정렬하여
                 * 다음 요청의 idAfter 비교 규칙을 일정하게 유지
                 */
                criteriaBuilder.asc(user.get("id"))
            );

        TypedQuery<User> query =
            entityManager.createQuery(criteriaQuery);

        /*
         * Service에서 hasNext를 판단할 수 있도록 요청 개수보다
         * 한 건 더 조회
         */
        query.setMaxResults(request.limit() + 1);

        return query.getResultList();
    }

    /**
     * 이메일·역할·잠금 상태 필터에 해당하는 전체 사용자 수를 조회
     *
     * totalCount는 현재 페이지 이후의 데이터 수가 아니라
     * 전체 필터 결과의 개수이므로 cursor와 idAfter는 적용하지 않는다.
     */
    @Override
    public long countUsers(UserListRequest request) {
        CriteriaBuilder criteriaBuilder =
            entityManager.getCriteriaBuilder();

        CriteriaQuery<Long> countQuery =
            criteriaBuilder.createQuery(Long.class);

        Root<User> user = countQuery.from(User.class);

        List<Predicate> predicates =
            buildFilterPredicates(
                criteriaBuilder,
                user,
                request
            );

        countQuery
            .select(criteriaBuilder.count(user))
            .where(predicates.toArray(Predicate[]::new));

        return entityManager
            .createQuery(countQuery)
            .getSingleResult();
    }

    /**
     * 선택적으로 전달되는 사용자 필터 조건을 생성
     */
    private List<Predicate> buildFilterPredicates(
        CriteriaBuilder criteriaBuilder,
        Root<User> user,
        UserListRequest request
    ) {
        List<Predicate> predicates = new ArrayList<>();

        if (request.emailLike() != null) {
            /*
             * 이메일 검색은 대소문자를 구분하지 않는다.
             *
             * 사용자가 입력한 %, _, \가 SQL LIKE 와일드카드로
             * 해석되지 않도록 먼저 이스케이프
             */
            String escapedEmail = escapeLikePattern(
                request.emailLike().toLowerCase(Locale.ROOT)
            );

            predicates.add(
                criteriaBuilder.like(
                    criteriaBuilder.lower(user.get("email")),
                    "%" + escapedEmail + "%",
                    '\\'
                )
            );
        }

        if (request.roleEqual() != null) {
            predicates.add(
                criteriaBuilder.equal(
                    user.get("role"),
                    request.roleEqual()
                )
            );
        }

        if (request.locked() != null) {
            predicates.add(
                criteriaBuilder.equal(
                    user.get("locked"),
                    request.locked()
                )
            );
        }

        return predicates;
    }

    /**
     * 정렬 기준에 맞는 커서 조건을 생성
     *
     * 커서 페이지네이션 조건은 다음 구조
     *
     * 오름차순:
     * sortValue > cursor
     * OR (sortValue = cursor AND id > idAfter)
     *
     * 내림차순:
     * sortValue < cursor
     * OR (sortValue = cursor AND id > idAfter)
     */
    private Predicate buildCursorPredicate(
        CriteriaBuilder criteriaBuilder,
        Root<User> user,
        UserListRequest request
    ) {
        boolean ascending = isAscending(request);

        return switch (request.sortBy()) {
            case SORT_NAME -> buildComparableCursorPredicate(
                criteriaBuilder,
                user.get("name"),
                CursorUtils.decode(request.cursor()),
                user,
                request.idAfter(),
                ascending
            );

            case SORT_EMAIL -> buildComparableCursorPredicate(
                criteriaBuilder,
                user.get("email"),
                CursorUtils.decode(request.cursor()),
                user,
                request.idAfter(),
                ascending
            );

            case SORT_CREATED_AT -> buildComparableCursorPredicate(
                criteriaBuilder,
                user.get("createdAt"),
                CursorUtils.decodeAsInstant(request.cursor()),
                user,
                request.idAfter(),
                ascending
            );

            case SORT_LOCKED -> buildComparableCursorPredicate(
                criteriaBuilder,
                user.get("locked"),
                decodeBooleanCursor(request.cursor()),
                user,
                request.idAfter(),
                ascending
            );

            case SORT_ROLE -> buildComparableCursorPredicate(
                criteriaBuilder,
                user.get("role"),
                UserRole.valueOf(
                    CursorUtils.decode(request.cursor())
                ),
                user,
                request.idAfter(),
                ascending
            );

            default -> throw new IllegalArgumentException(
                "지원하지 않는 사용자 정렬 기준입니다."
            );
        };
    }

    /**
     * Comparable 타입의 정렬 필드에 공통 커서 비교 조건을 적용
     *
     * String, Instant, Boolean, UserRole 모두 Comparable이므로
     * 하나의 메서드로 동일한 커서 규칙을 사용할 수 있다.
     */
    private <T extends Comparable<? super T>>
    Predicate buildComparableCursorPredicate(
        CriteriaBuilder criteriaBuilder,
        Path<T> sortPath,
        T cursorValue,
        Root<User> user,
        UUID idAfter,
        boolean ascending
    ) {
        Predicate primaryComparison = ascending
            ? criteriaBuilder.greaterThan(sortPath, cursorValue)
            : criteriaBuilder.lessThan(sortPath, cursorValue);

        Predicate sameValueAndIdAfter = criteriaBuilder.and(
            criteriaBuilder.equal(sortPath, cursorValue),
            criteriaBuilder.greaterThan(
                user.<UUID>get("id"),
                idAfter
            )
        );

        return criteriaBuilder.or(
            primaryComparison,
            sameValueAndIdAfter
        );
    }

    /**
     * cursor와 idAfter는 하나의 커서 쌍이므로
     * 둘 중 하나만 전달된 요청은 거부
     */
    private void validateCursorPair(UserListRequest request) {
        boolean hasCursor = request.cursor() != null;
        boolean hasIdAfter = request.idAfter() != null;

        if (hasCursor != hasIdAfter) {
            throw new IllegalArgumentException(
                "cursor와 idAfter는 함께 전달해야 합니다."
            );
        }
    }

    /**
     * 문자열 boolean 커서를 엄격하게 변환
     *
     * Boolean.valueOf()는 true 이외의 모든 문자열을 false로 처리하므로
     * 잘못된 커서도 정상 값으로 오인할 수 있다.
     * 따라서 true와 false만 명시적으로 허용
     */
    private Boolean decodeBooleanCursor(String cursor) {
        String decoded = CursorUtils.decode(cursor);

        if ("true".equals(decoded)) {
            return true;
        }

        if ("false".equals(decoded)) {
            return false;
        }

        throw new IllegalArgumentException(
            "잠금 상태 커서 형식이 올바르지 않습니다."
        );
    }

    private boolean isAscending(UserListRequest request) {
        return DIRECTION_ASCENDING.equals(
            request.sortDirection()
        );
    }

    /**
     * SQL LIKE에서 특별한 의미를 갖는 문자를 일반 문자로 검색하도록
     * 백슬래시, 퍼센트 및 언더스코어를 이스케이프한다.
     */
    private String escapeLikePattern(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
    }
}
