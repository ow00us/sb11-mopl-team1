package com.mopl.user.service;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.dto.OAuthAccountDto;
import com.mopl.user.repository.OAuthAccountRepository;
import com.mopl.user.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
    private final OAuthAccountRepository
        oauthAccountRepository;

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
