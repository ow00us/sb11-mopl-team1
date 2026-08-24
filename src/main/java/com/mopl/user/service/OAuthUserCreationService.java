package com.mopl.user.service;

import com.mopl.user.entity.OAuthAccount;
import com.mopl.user.entity.OAuthProvider;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.OAuthAccountRepository;
import com.mopl.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 신규 OAuth 사용자와 외부 계정 연결 정보를 별도 트랜잭션에서 생성
 *
 * <p>동일한 Provider 계정으로 최초 로그인 요청이 동시에 들어오면
 * DB 유일성 제약에 의해 한 트랜잭션만 성공할 수 있습니다.</p>
 *
 * <p>생성 트랜잭션을 별도로 분리하면 충돌한 트랜잭션이 완전히
 * 롤백된 이후 호출 서비스에서 승리한 연결 정보를 다시 조회할 수 있습니다.</p>
 */
@Service
@RequiredArgsConstructor
public class OAuthUserCreationService {

    private final UserRepository userRepository;

    private final OAuthAccountRepository
        oauthAccountRepository;

    /**
     * OAuth 전용 사용자와 Provider 연결 정보를 함께 생성
     *
     * <p>OAuthAccount 저장이 실패하면 User 생성도 함께 롤백됩니다.
     * 데이터 충돌 예외는 여기서 삼키지 않고 호출 서비스로 전달합니다.</p>
     *
     * @param provider       OAuth Provider
     * @param providerUserId Provider가 발급한 사용자 고유 식별자
     * @param email          정규화·검증된 이메일
     * @param name           검증된 사용자 이름
     * @param profileImageUrl 검증된 프로필 이미지 URL
     * @return 생성된 MOPL 사용자
     */
    @Transactional(
        propagation = Propagation.REQUIRES_NEW
    )
    public User create(
        OAuthProvider provider,
        String providerUserId,
        String email,
        String name,
        String profileImageUrl
    ) {
        User user =
            User.builder()
                .email(email)
                .passwordHash(null)
                .name(name)
                .profileImageUrl(profileImageUrl)
                .role(UserRole.USER)
                .locked(false)
                .build();

        User savedUser =
            userRepository.save(user);

        OAuthAccount oauthAccount =
            OAuthAccount.builder()
                .user(savedUser)
                .provider(provider)
                .providerUserId(providerUserId)
                .build();

        /*
         * User INSERT와 OAuthAccount INSERT를 이 트랜잭션 안에서
         * 즉시 실행해 유일성 제약 충돌을 호출 서비스에 전달
         */
        oauthAccountRepository.saveAndFlush(
            oauthAccount
        );

        return savedUser;
    }
}
