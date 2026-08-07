package com.mopl.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mopl.global.config.JpaConfig;
import com.mopl.global.util.CursorUtils;
import com.mopl.user.dto.UserListRequest;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 관리자 사용자 목록 조회 Repository의 실제 PostgreSQL 동작을 검증
 *
 * 단순히 메서드 호출 여부를 확인하는 테스트가 아니라
 * Testcontainers PostgreSQL에 사용자를 저장한 뒤 다음 기능을 확인
 *
 * 1. 이메일·역할·잠금 상태 필터
 * 2. 전체 필터 결과 개수
 * 3. 모든 정렬 기준의 오름차순·내림차순
 * 4. cursor와 idAfter를 이용한 다음 페이지 조회
 * 5. 동일한 정렬 값을 가진 사용자의 중복·누락 방지
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({
    JpaConfig.class,
    UserRepositoryCustomImpl.class
})
@AutoConfigureTestDatabase(
    replace = AutoConfigureTestDatabase.Replace.NONE
)
@Testcontainers
class UserListRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("이메일, 역할, 잠금 상태 필터를 모두 적용해 사용자를 조회한다")
    void findUsers_success_withFilters() {
        // given
        persistUser(
            "normal@example.com",
            "일반 사용자",
            UserRole.USER,
            false
        );

        User expected = persistUser(
            "locked@example.com",
            "잠긴 사용자",
            UserRole.USER,
            true
        );

        persistUser(
            "admin@example.com",
            "관리자",
            UserRole.ADMIN,
            true
        );

        flushAndClear();

        /*
         * 이메일에는 LOCKED가 포함되고,
         * 역할은 USER이며,
         * 잠긴 상태인 사용자만 조회
         *
         * 이메일 검색어를 대문자로 전달하여
         * 대소문자를 구분하지 않는 검색도 함께 확인
         */
        UserListRequest request = new UserListRequest(
            "LOCKED",
            UserRole.USER,
            true,
            null,
            null,
            20,
            "ASCENDING",
            "email"
        );

        // when
        List<User> result = userRepository.findUsers(request);
        long totalCount = userRepository.countUsers(request);

        // then
        assertThat(result)
            .extracting(User::getId)
            .containsExactly(expected.getId());

        assertThat(totalCount).isEqualTo(1);
    }

    @Test
    @DisplayName("이메일 검색의 LIKE 특수문자를 일반 문자로 처리한다")
    void findUsers_escapeLikeWildcards() {
        // given
        User expected = persistUser(
            "percent%user@example.com",
            "퍼센트 사용자",
            UserRole.USER,
            false
        );

        persistUser(
            "percentxuser@example.com",
            "일반 사용자",
            UserRole.USER,
            false
        );

        flushAndClear();

        /*
         * %가 와일드카드로 처리된다면 두 사용자 모두 조회된다.
         * 일반 문자로 이스케이프되면 이메일에 실제 % 문자가 있는
         * 사용자만 조회되어야 한다.
         */
        UserListRequest request = new UserListRequest(
            "%user",
            null,
            null,
            null,
            null,
            20,
            "ASCENDING",
            "email"
        );

        // when
        List<User> result = userRepository.findUsers(request);

        // then
        assertThat(result)
            .extracting(User::getId)
            .containsExactly(expected.getId());
    }

    /**
     * OpenAPI에서 지원하는 모든 정렬 기준과 방향에 대해
     * 첫 번째 사용자 이후의 목록을 커서로 조회
     *
     * 테스트 사용자 중에는 같은 이름, 역할 및 잠금 상태를 가진
     * 사용자가 포함되어 있으므로 idAfter 보조 커서도 함께 검증
     */
    @ParameterizedTest(
        name = "sortBy={0}, sortDirection={1}"
    )
    @CsvSource({
        "name, ASCENDING",
        "name, DESCENDING",
        "email, ASCENDING",
        "email, DESCENDING",
        "createdAt, ASCENDING",
        "createdAt, DESCENDING",
        "locked, ASCENDING",
        "locked, DESCENDING",
        "role, ASCENDING",
        "role, DESCENDING"
    })
    @DisplayName("모든 정렬 기준과 방향에서 다음 커서 조회가 동작한다")
    void findUsers_success_withEverySortAndCursor(
        String sortBy,
        String sortDirection
    ) {
        // given
        List<User> savedUsers = List.of(
            persistUser(
                "alpha@example.com",
                "같은 이름",
                UserRole.USER,
                false
            ),
            persistUser(
                "bravo@example.com",
                "같은 이름",
                UserRole.ADMIN,
                false
            ),
            persistUser(
                "charlie@example.com",
                "다른 이름",
                UserRole.USER,
                true
            )
        );

        flushAndClear();

        /*
         * 데이터베이스에서 기대되는 정렬 순서를 테스트 코드에서도
         * 동일하게 만든 뒤 첫 번째 사용자를 커서 기준으로 사용
         */
        List<User> sortedUsers = new ArrayList<>(savedUsers);

        sortedUsers.sort(
            comparatorFor(sortBy, sortDirection)
        );

        User cursorUser = sortedUsers.get(0);

        UserListRequest request = new UserListRequest(
            null,
            null,
            null,
            cursorFor(cursorUser, sortBy),
            cursorUser.getId(),
            100,
            sortDirection,
            sortBy
        );

        // when
        List<User> result = userRepository.findUsers(request);

        // then
        List<UUID> expectedIds = sortedUsers
            .subList(1, sortedUsers.size())
            .stream()
            .map(User::getId)
            .toList();

        assertThat(result)
            .extracting(User::getId)
            .containsExactlyElementsOf(expectedIds);
    }

    @Test
    @DisplayName("cursor와 idAfter 중 하나만 전달하면 조회를 거부한다")
    void findUsers_fail_whenCursorPairIsIncomplete() {
        // given
        UserListRequest request = new UserListRequest(
            null,
            null,
            null,
            CursorUtils.encode("user@example.com"),
            null,
            20,
            "ASCENDING",
            "email"
        );

        // when & then
        assertThatThrownBy(
            () -> userRepository.findUsers(request)
        )
            /*
             * @Repository 경계를 통과하면서 잘못된 Repository 사용에 관한
             * IllegalArgumentException이 Spring의 데이터 접근 예외로 변환
             */
            .isInstanceOf(
                InvalidDataAccessApiUsageException.class
            )
            /*
             * 예외가 단순히 같은 외부 타입인지만 확인하지 않고,
             * 우리가 의도한 IllegalArgumentException이 근본 원인인지 확인
             */
            .hasRootCauseInstanceOf(
                IllegalArgumentException.class
            )
            .hasRootCauseMessage(
                "cursor와 idAfter는 함께 전달해야 합니다."
            );
    }

    /**
     * 정렬 기준에 맞춰 마지막 사용자의 값을 Base64 커서로 변환
     */
    private String cursorFor(
        User user,
        String sortBy
    ) {
        return switch (sortBy) {
            case "name" ->
                CursorUtils.encode(user.getName());

            case "email" ->
                CursorUtils.encode(user.getEmail());

            case "createdAt" ->
                CursorUtils.encodeInstant(user.getCreatedAt());

            case "locked" ->
                CursorUtils.encode(
                    Boolean.toString(user.isLocked())
                );

            case "role" ->
                CursorUtils.encode(user.getRole().name());

            default ->
                throw new IllegalArgumentException(
                    "지원하지 않는 테스트 정렬 기준입니다."
                );
        };
    }

    /**
     * Repository의 정렬 규칙과 동일한 Comparator를 생성
     *
     * 주 정렬 값은 요청 방향을 따르지만,
     * 주 정렬 값이 같을 때 UUID는 항상 오름차순으로 정렬
     */
    private Comparator<User> comparatorFor(
        String sortBy,
        String sortDirection
    ) {
        Comparator<User> primaryComparator =
            switch (sortBy) {
                case "name" ->
                    Comparator.comparing(User::getName);

                case "email" ->
                    Comparator.comparing(User::getEmail);

                case "createdAt" ->
                    Comparator.comparing(User::getCreatedAt);

                case "locked" ->
                    Comparator.comparing(User::isLocked);

                /*
                 * role은 DB에 문자열로 저장되므로 enum 선언 순서가 아니라
                 * 실제 저장 문자열인 ADMIN, USER의 문자열 순서로 비교
                 */
                case "role" ->
                    Comparator.comparing(
                        user -> user.getRole().name()
                    );

                default ->
                    throw new IllegalArgumentException(
                        "지원하지 않는 테스트 정렬 기준입니다."
                    );
            };

        if ("DESCENDING".equals(sortDirection)) {
            primaryComparator = primaryComparator.reversed();
        }

        /*
         * PostgreSQL UUID 정렬 순서와 맞추기 위해 UUID 문자열을
         * 보조 정렬 기준으로 사용
         */
        return primaryComparator.thenComparing(
            user -> user.getId().toString()
        );
    }

    /**
     * 테스트용 사용자를 영속성 컨텍스트에 저장
     */
    private User persistUser(
        String email,
        String name,
        UserRole role,
        boolean locked
    ) {
        User user = User.builder()
            .email(email)
            .passwordHash("encoded-password")
            .name(name)
            .role(role)
            .locked(locked)
            .build();

        entityManager.persist(user);

        return user;
    }

    /**
     * INSERT SQL을 PostgreSQL에 반영한 뒤 영속성 컨텍스트를 비운다.
     *
     * 이후 Repository 조회가 메모리에 있는 엔티티가 아니라
     * 실제 데이터베이스 쿼리 결과를 반환하도록 한다.
     */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
