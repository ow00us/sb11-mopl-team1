package com.mopl.user.repository;

import com.mopl.user.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID>, UserRepositoryCustom {
    // 정규화된 이메일로 사용자 조회

    /**
     * 다음과 같은 의미의 조회 쿼리를 자동으로 생성
     *
     * SELECT * FROM users WHERE email = ?
     */
    // 조회 결과 없을 수 있으므로 null 대신 optional로 반환
    // @Param email 앞뒤 공백 제거, 소문자로 변환된 이메일
    // @return 해당 이메일의 사용자, 존재하지 않으면 빈 Optional
    Optional<User> findByEmail(String email);

    // 정규화된 이메일을 가진 사용자가 이미 존재하는지 확인
    // 회원가입 전에 중복 이메일 빠르게 확인하는데 사용
    boolean existsByEmail(String email);

}
