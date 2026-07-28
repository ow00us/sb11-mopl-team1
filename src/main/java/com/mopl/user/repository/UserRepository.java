package com.mopl.user.repository;

import com.mopl.user.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {
    // 정규화된 이메일로 사용자 조회

    /**
     * 다음과 같은 의미의 조회 쿼리를 자동으로 생성합니다.</p>
     * <p>
     * SELECT * FROM users WHERE email = ?
     */
    // 조회 결과 없을 수 있으므로 null 대신 optional로 반환
    // @Param email 앞뒤 공백 제거, 소문자로 변환된 이메일
    // @return 해당 이메일의 사용자, 존재하지 않으면 빈 Optional
    Optional<User> findByEmail(String email);

}
