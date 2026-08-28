package com.mopl.user.service;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.entity.User;
import com.mopl.user.repository.OAuthAccountRepository;
import com.mopl.user.repository.UserRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 탈퇴에 필요한 데이터베이스 변경을 하나의 트랜잭션으로 처리
 *
 * 사용자 익명화와 OAuth 연결 삭제 중 하나라도 실패하면
 * 전체 데이터베이스 변경을 롤백
 */
@Service
@RequiredArgsConstructor
public class UserWithdrawalTransactionService {

    private final UserRepository userRepository;
    private final OAuthAccountRepository oauthAccountRepository;
    private final AccessTokenBlockLifecycleService accessTokenBlockLifecycleService;

    /**
     * 인증된 본인 계정을 탈퇴 처리
     *
     * @param authenticatedUserId 인증된 사용자 UUID
     * @param userId 탈퇴 대상 사용자 UUID
     */
    @Transactional
    public void withdraw(
        UUID authenticatedUserId,
        UUID userId
    ) {
        validateSelf(
            authenticatedUserId,
            userId
        );

        /*
         * 동일 사용자의 탈퇴·계정 연결·연결 해제 요청을 직렬화
         * findByIdForUpdate는 탈퇴 사용자를 조회하지 않는다.
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
         * 사용자 행 잠금을 획득한 상태에서 기존 Access Token을 차단합니다.
         * 잠금 해제 요청과 탈퇴 요청이 동시에 실행돼도,
         * 잠금 해제의 afterCommit이 탈퇴 차단 상태를 뒤늦게 제거하지 못하게 합니다.
         */
        accessTokenBlockLifecycleService.block(
            userId
        );

        /*
         * 동일한 Provider 계정으로 신규 가입할 수 있도록
         * 외부 Provider 식별 정보를 제거
         */
        oauthAccountRepository.deleteAllByUserId(
            userId
        );

        /*
         * 사용자 UUID는 유지하고 개인정보와 로컬 로그인 수단을
         * 익명화한 뒤 탈퇴 시각을 기록
         */
        user.withdraw(
            Instant.now()
        );
    }

    private void validateSelf(
        UUID authenticatedUserId,
        UUID userId
    ) {
        if (authenticatedUserId == null) {
            throw new BusinessException(
                ErrorCode.UNAUTHORIZED
            );
        }

        if (
            userId == null
                || !authenticatedUserId.equals(userId)
        ) {
            throw new BusinessException(
                ErrorCode.FORBIDDEN
            );
        }
    }
}
