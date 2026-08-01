package com.mopl.follow.service;

import com.mopl.follow.dto.FollowDto;
import com.mopl.follow.dto.FollowUserItemDto;
import com.mopl.follow.entity.Follow;
import com.mopl.follow.repository.FollowRepository;
import com.mopl.global.common.CursorResponse;
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
    public FollowDto follow(UUID followerId, UUID followeeId) {
        if (followerId.equals(followeeId)) {
            throw new BusinessException(ErrorCode.FOLLOW_SELF);
        }
        if (!userRepository.existsById(followeeId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (followRepository.existsByFollowerIdAndFolloweeId(followerId, followeeId)) {
            throw new BusinessException(ErrorCode.FOLLOW_DUPLICATE);
        }
        try {
            Follow follow = followRepository.saveAndFlush(
                    Follow.builder().followerId(followerId).followeeId(followeeId).build());
            return FollowDto.from(follow);
        } catch (DataIntegrityViolationException e) {
            if (followRepository.existsByFollowerIdAndFolloweeId(followerId, followeeId)) {
                throw new BusinessException(ErrorCode.FOLLOW_DUPLICATE);
            }
            throw e;
        }
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

    /** Red 스텁: Green 단계에서 커서 페이지네이션 구현 예정. */
    public CursorResponse<FollowUserItemDto> getFollowers(
            UUID followeeId, String cursor, UUID idAfter,
            int limit, String sortBy, String sortDirection) {
        throw new UnsupportedOperationException("Green 단계에서 구현");
    }

    /** Red 스텁: Green 단계에서 커서 페이지네이션 구현 예정. */
    public CursorResponse<FollowUserItemDto> getFollowings(
            UUID followerId, String cursor, UUID idAfter,
            int limit, String sortBy, String sortDirection) {
        throw new UnsupportedOperationException("Green 단계에서 구현");
    }
}