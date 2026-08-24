package com.mopl.user.service;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.entity.OAuthAccount;
import com.mopl.user.entity.OAuthProvider;
import com.mopl.user.entity.User;
import com.mopl.user.dto.OAuthAccountDto;
import com.mopl.user.repository.OAuthAccountRepository;
import com.mopl.user.repository.UserRepository;
import com.mopl.user.storage.RefreshTokenStore;
import com.mopl.user.security.oauth.link.OAuthLinkIntent;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인증된 사용자의 OAuth 계정 연결 정보를 관리하는 서비스
 *
 * <p>계정 연결, 목록 조회 및 연결 해제 과정에서 현재 인증된 사용자와
 * 요청 대상 사용자가 동일한지 확인합니다.</p>
 */
@Service
@RequiredArgsConstructor
public class OAuthAccountManagementService {

    private final UserRepository userRepository;
    private final OAuthAccountRepository oauthAccountRepository;
    private final RefreshTokenStore refreshTokenStore;

    /**
     * 사용자에게 연결된 OAuth 계정 목록을 조회
     *
     * <p>다른 사용자의 연결 정보를 조회할 수 없도록 사용자 존재 여부를
     * 확인하기 전에 인증 사용자와 대상 사용자 UUID를 비교합니다.</p>
     *
     * @param authenticatedUserId JWT에서 복원한 현재 사용자 UUID
     * @param userId 연결 계정을 조회할 대상 사용자 UUID
     * @return 연결 시각 오름차순으로 정렬된 OAuth 계정 목록
     * @throws BusinessException 인증 정보가 없는 경우
     * @throws BusinessException 다른 사용자의 연결 정보를 조회하는 경우
     * @throws BusinessException 대상 사용자가 존재하지 않는 경우
     */
    @Transactional(readOnly = true)
    public List<OAuthAccountDto> getLinkedAccounts(
        UUID authenticatedUserId,
        UUID userId
    ) {
        validateSelf(
            authenticatedUserId,
            userId
        );

        if (!userRepository.existsById(userId)) {
            throw new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND
            );
        }

        return oauthAccountRepository
            .findAllByUserIdOrderByCreatedAtAsc(
                userId
            )
            .stream()
            .map(OAuthAccountDto::from)
            .toList();
    }

    /**
     * OAuth 계정 연결 시작 요청이 유효한지 검증
     *
     * <p>실제 Provider 계정 연결은 Provider 인증 성공 이후 수행합니다.
     * 이 메서드는 연결 의도를 세션에 저장하기 전에 본인 요청인지,
     * 사용자가 존재하는지, 같은 Provider가 이미 연결됐는지 확인합니다.</p>
     *
     * @param authenticatedUserId JWT에서 복원한 현재 사용자 UUID
     * @param userId OAuth 계정을 연결할 사용자 UUID
     * @param provider 연결할 OAuth Provider
     */
    @Transactional(readOnly = true)
    public void validateLinkStart(
        UUID authenticatedUserId,
        UUID userId,
        OAuthProvider provider
    ) {
        validateSelf(
            authenticatedUserId,
            userId
        );

        if (provider == null) {
            throw new BusinessException(
                ErrorCode.INVALID_INPUT
            );
        }

        if (!userRepository.existsById(userId)) {
            throw new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND
            );
        }

        /*
         * 한 사용자는 Provider별로 하나의 계정만 연결할 수 있다.
         * 다른 Provider 계정 연결은 허용
         */
        if (oauthAccountRepository
            .existsByUserIdAndProvider(
                userId,
                provider
            )) {
            throw new BusinessException(
                ErrorCode.OAUTH_ACCOUNT_CONFLICT
            );
        }
    }

    /**
     * Provider 인증이 완료된 OAuth 계정을 기존 사용자에게 연결
     *
     * <p>linkIntent는 JWT 인증을 거쳐 세션에 저장되고, OAuth Callback에서
     * 한 번만 소비된 연결 의도여야 합니다.</p>
     *
     * <p>같은 외부 계정이 이미 동일 사용자에게 연결돼 있으면 Callback
     * 재처리로 보고 멱등하게 기존 사용자를 반환합니다. 다른 사용자에게
     * 연결됐거나 대상 사용자가 같은 Provider의 다른 계정을 가지고 있으면
     * 연결 충돌로 거부합니다.</p>
     *
     * @param linkIntent 세션에서 소비한 일회성 연결 의도
     * @param authenticatedProvider 실제 인증을 완료한 Provider
     * @param providerUserId Provider가 검증해 반환한 사용자 식별자
     * @return OAuth 계정이 연결된 MOPL 사용자
     */
    @Transactional
    public User linkVerifiedAccount(
        OAuthLinkIntent linkIntent,
        OAuthProvider authenticatedProvider,
        String providerUserId
    ) {
        if (linkIntent == null
            || authenticatedProvider == null
            || linkIntent.provider()
            != authenticatedProvider
            || providerUserId == null
            || providerUserId.isBlank()) {
            throw new BusinessException(
                ErrorCode.INVALID_INPUT
            );
        }

        /*
         * 외부 식별자는 조회와 저장에서 반드시 같은 값을 사용해야 한다.
         * 앞뒤 공백을 제거한 값을 이후 모든 Repository 호출에 사용한
         */
        String normalizedProviderUserId =
            providerUserId.strip();

        if (normalizedProviderUserId.length() > 255) {
            throw new BusinessException(
                ErrorCode.INVALID_INPUT
            );
        }

        UUID userId =
            linkIntent.userId();

        /*
         * 같은 사용자의 동시 연결·해제 요청을 직렬화
         */
        User user =
            userRepository
                .findByIdForUpdate(userId)
                .orElseThrow(() ->
                    new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND
                    )
                );

        /*
         * Provider 계정은 전체 서비스에서 한 사용자에게만 연결할 수 있다.
         */
        var existingProviderAccount =
            oauthAccountRepository
                .findByProviderAndProviderUserId(
                    authenticatedProvider,
                    normalizedProviderUserId
                );

        if (existingProviderAccount.isPresent()) {
            OAuthAccount existingAccount =
                existingProviderAccount.orElseThrow();

            if (existingAccount
                .getUser()
                .getId()
                .equals(userId)) {
                /*
                 * 동일 Callback 재처리는 새로운 연결 정보를 만들지 않는다.
                 */
                return user;
            }

            throw new BusinessException(
                ErrorCode.OAUTH_ACCOUNT_CONFLICT
            );
        }

        /*
         * 사용자는 같은 Provider의 서로 다른 계정을
         * 두 개 이상 연결할 수 없다.
         */
        if (oauthAccountRepository
            .existsByUserIdAndProvider(
                userId,
                authenticatedProvider
            )) {
            throw new BusinessException(
                ErrorCode.OAUTH_ACCOUNT_CONFLICT
            );
        }

        OAuthAccount oauthAccount =
            OAuthAccount.builder()
                .user(user)
                .provider(authenticatedProvider)
                .providerUserId(
                    normalizedProviderUserId
                )
                .build();

        try {
            /*
             * 사전 조회와 INSERT 사이에 다른 요청이 먼저 저장할 수 있으므로
             * DB 고유 제약을 최종 동시성 방어선으로 사용
             */
            oauthAccountRepository
                .saveAndFlush(
                    oauthAccount
                );
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(
                ErrorCode.OAUTH_ACCOUNT_CONFLICT
            );
        }

        return user;
    }

    /**
     * 본인에게 연결된 OAuth 계정을 해제
     *
     * <p>동시 해제 요청이 OAuth 전용 사용자의 마지막 로그인 수단을
     * 모두 제거하지 못하도록 사용자 행을 쓰기 잠금으로 조회합니다.</p>
     *
     * <p>로컬 비밀번호가 없는 사용자는 OAuth 계정이 하나만 남은 경우
     * 해당 계정을 해제할 수 없습니다.</p>
     *
     * @param authenticatedUserId JWT에서 복원한 현재 사용자 UUID
     * @param userId OAuth 연결을 해제할 대상 사용자 UUID
     * @param provider 연결을 해제할 OAuth Provider
     */
    @Transactional
    public void unlinkAccount(
        UUID authenticatedUserId,
        UUID userId,
        OAuthProvider provider
    ) {
        validateSelf(
            authenticatedUserId,
            userId
        );

        if (provider == null) {
            throw new BusinessException(
                ErrorCode.INVALID_INPUT
            );
        }

        /*
         * 같은 사용자의 동시 연결 해제 요청을 직렬화
         * 두 요청이 각각 다른 Provider를 해제하더라도
         * 마지막 로그인 수단 검사를 순서대로 수행하게 된다.
         */
        User user =
            userRepository
                .findByIdForUpdate(userId)
                .orElseThrow(() ->
                    new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND
                    )
                );

        OAuthAccount oauthAccount =
            oauthAccountRepository
                .findByUserIdAndProvider(
                    userId,
                    provider
                )
                .orElseThrow(() ->
                    new BusinessException(
                        ErrorCode.OAUTH_ACCOUNT_NOT_FOUND
                    )
                );

        /*
         * passwordHash가 null이면 이메일·비밀번호 로그인을 사용할 수 없는
         * OAuth 전용 사용자
         *
         * 연결 계정이 하나만 남은 경우 이를 삭제하면 로그인 가능한 수단이
         * 사라지므로 연결 해제를 거부
         */
        if (user.getPasswordHash() == null
            && oauthAccountRepository
            .countByUserId(userId) <= 1) {
            throw new BusinessException(
                ErrorCode.OAUTH_LAST_LOGIN_METHOD
            );
        }

        oauthAccountRepository.delete(
            oauthAccount
        );

        /*
         * DELETE SQL을 Redis 세션 폐기보다 먼저 실행하여
         * 데이터베이스 제약 오류를 가능한 한 먼저 확인
         *
         * 이후 Redis 폐기가 실패하면 런타임 예외가 전파되고
         * 현재 DB 트랜잭션도 롤백된다.
         */
        oauthAccountRepository.flush();

        /*
         * 어떤 Refresh Token 세션이 해제한 Provider 로그인으로 생성됐는지
         * 현재 저장 구조에서는 구분하지 않으므로 모든 세션을 폐기
         */
        refreshTokenStore.revokeAllByUserId(
            userId
        );
    }

    /**
     * 인증된 사용자와 요청 대상 사용자가 동일한지 검증
     *
     * <p>사용자 조회 전에 권한을 확인하여 다른 사용자의 존재 여부가
     * 응답 상태를 통해 노출되지 않도록 합니다.</p>
     */
    private void validateSelf(
        UUID authenticatedUserId,
        UUID userId
    ) {
        if (authenticatedUserId == null) {
            throw new BusinessException(
                ErrorCode.UNAUTHORIZED
            );
        }

        if (userId == null
            || !authenticatedUserId.equals(userId)) {
            throw new BusinessException(
                ErrorCode.FORBIDDEN
            );
        }
    }
}
