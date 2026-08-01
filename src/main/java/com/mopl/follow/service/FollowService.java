package com.mopl.follow.service;

import com.mopl.follow.dto.FollowDto;
import com.mopl.follow.entity.Follow;
import com.mopl.follow.repository.FollowRepository;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FollowService {

    private final FollowRepository followRepository;
    private final UserRepository userRepository;

    @Transactional
    public FollowResult follow(UUID followerId, UUID followeeId) {
        if (followerId.equals(followeeId)) {
            throw new BusinessException(ErrorCode.FOLLOW_SELF);
        }
        if (!userRepository.existsById(followeeId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        // 사전 중복 체크: 이미 팔로우 중이면 기존 관계를 그대로 반환 (ADR 2 - 200)
        if (followRepository.existsByFollowerIdAndFolloweeId(followerId, followeeId)) {
            return existingResult(followerId, followeeId);
        }
        try {
            Follow follow = followRepository.saveAndFlush(
                    Follow.builder().followerId(followerId).followeeId(followeeId).build());
            return new FollowResult(FollowDto.from(follow), true);
        } catch (DataIntegrityViolationException e) {
            // 사전 체크와 저장 사이의 동시 팔로우 요청으로 유니크 제약 위반 시,
            // 이미 존재하는 관계로 간주하고 조회 후 기존 값을 반환한다.
            return followRepository.findByFollowerIdAndFolloweeId(followerId, followeeId)
                    .map(existing -> new FollowResult(FollowDto.from(existing), false))
                    .orElseThrow(() -> e);
        }
    }

    private FollowResult existingResult(UUID followerId, UUID followeeId) {
        Follow existing = followRepository.findByFollowerIdAndFolloweeId(followerId, followeeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return new FollowResult(FollowDto.from(existing), false);
    }

    @Transactional
    public void unfollow(UUID followId, UUID requesterId) {
        Follow follow = followRepository.findById(followId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!follow.getFollowerId().equals(requesterId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        followRepository.delete(follow);
    }

    public long countFollowers(UUID followeeId) {
        return followRepository.countByFolloweeId(followeeId);
    }

    public FollowDto getFollowedByMe(UUID followerId, UUID followeeId) {
        return followRepository.findByFollowerIdAndFolloweeId(followerId, followeeId)
                .map(FollowDto::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }
}