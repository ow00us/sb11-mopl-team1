package com.mopl.follow.service;

import com.mopl.follow.dto.FollowDto;
import com.mopl.follow.dto.FollowUserItemDto;
import com.mopl.follow.entity.Follow;
import com.mopl.follow.repository.FollowRepository;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.common.UserSummary;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.util.CursorUtils;
import com.mopl.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

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

        // 예외 없는 upsert 로 삽입 시도. 이미 존재하면 rows=0 이 반환되고
        // 트랜잭션은 그대로 유지되어 후속 조회가 안전하다.
        int inserted = followRepository.insertIfAbsent(followerId.toString(), followeeId.toString());
        Optional<Follow> found = followRepository.findByFollowerIdAndFolloweeId(followerId, followeeId);

        if (found.isEmpty()) {
            // rows=0 이었는데 조회도 비어 있는 유일한 경로:
            // upsert 와 findBy 사이에 다른 트랜잭션이 unfollow 로 같은 행을 삭제한 경우.
            // 이때는 한 번만 재시도해서 새 관계를 만들고 결과를 돌려준다.
            // 재시도 후에도 없으면 심각한 일관성 문제이므로 500 으로 노출한다.
            if (inserted == 0) {
                int retryInserted = followRepository.insertIfAbsent(
                        followerId.toString(), followeeId.toString());
                Follow refetched = followRepository
                        .findByFollowerIdAndFolloweeId(followerId, followeeId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
                return new FollowResult(FollowDto.from(refetched), retryInserted == 1);
            }
            // inserted=1 인데 조회가 비어 있으면 방금 넣은 것이 사라진 셈이라
            // 재시도로도 해결되지 않는 인프라 이상. 그대로 500.
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }

        return new FollowResult(FollowDto.from(found.get()), inserted == 1);
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

    public CursorResponse<FollowUserItemDto> getFollowers(
            UUID followeeId, String cursor, UUID idAfter,
            int limit, String sortBy, String sortDirection) {
        return fetchCursorPage(
                cursor, idAfter, limit, sortBy, sortDirection,
                (cursorTime) -> followRepository.findFollowersByFolloweeIdDesc(
                        followeeId.toString(),
                        cursorTime,
                        idAfter != null ? idAfter.toString() : null,
                        limit + 1),
                Follow::getFollowerId,
                () -> followRepository.countByFolloweeId(followeeId));
    }

    public CursorResponse<FollowUserItemDto> getFollowings(
            UUID followerId, String cursor, UUID idAfter,
            int limit, String sortBy, String sortDirection) {
        return fetchCursorPage(
                cursor, idAfter, limit, sortBy, sortDirection,
                (cursorTime) -> followRepository.findFollowingsByFollowerIdDesc(
                        followerId.toString(),
                        cursorTime,
                        idAfter != null ? idAfter.toString() : null,
                        limit + 1),
                Follow::getFolloweeId,
                () -> followRepository.countByFollowerId(followerId));
    }

    /**
     * 팔로워/팔로잉 목록에 공통으로 쓰이는 커서 페이지네이션 조립 로직.
     * userIdExtractor 로 팔로우 관계에서 응답에 노출할 사용자 ID를 선택한다.
     */
    private CursorResponse<FollowUserItemDto> fetchCursorPage(
            String cursor, UUID idAfter, int limit,
            String sortBy, String sortDirection,
            Function<Instant, List<Follow>> pageFetcher,
            Function<Follow, UUID> userIdExtractor,
            java.util.function.LongSupplier totalCounter) {

        if ((cursor != null) != (idAfter != null)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        Instant cursorTime;
        try {
            cursorTime = (cursor != null) ? CursorUtils.decodeAsInstant(cursor) : null;
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        List<Follow> rows = pageFetcher.apply(cursorTime);
        int fetchSize = limit + 1;
        boolean hasNext = rows.size() == fetchSize;
        List<Follow> page = hasNext ? rows.subList(0, limit) : rows;

        String nextCursor  = null;
        UUID   nextIdAfter = null;
        if (hasNext && !page.isEmpty()) {
            Follow last = page.get(page.size() - 1);
            nextCursor  = CursorUtils.encodeInstant(last.getCreatedAt());
            nextIdAfter = last.getId();
        }

        List<FollowUserItemDto> data = page.stream()
                .map(f -> new FollowUserItemDto(
                        f.getId(),
                        new UserSummary(userIdExtractor.apply(f), null, null),
                        f.getCreatedAt()))
                .toList();

        return CursorResponse.of(data, nextCursor, nextIdAfter, hasNext,
                totalCounter.getAsLong(), sortBy, sortDirection);
    }
}