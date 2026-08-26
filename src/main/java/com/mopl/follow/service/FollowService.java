package com.mopl.follow.service;

import com.mopl.follow.dto.FollowDto;
import com.mopl.follow.dto.FollowRecommendationItemDto;
import com.mopl.follow.dto.FollowUserItemDto;
import com.mopl.follow.entity.Follow;
import com.mopl.follow.event.FollowEventFactory;
import com.mopl.follow.repository.FollowRecommendationRow;
import com.mopl.follow.repository.FollowRepository;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.common.UserSummary;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.outbox.OutboxRecorder;
import com.mopl.global.event.EventEnvelope;
import com.mopl.global.event.KafkaEventContract;
import com.mopl.global.util.CursorUtils;
import com.mopl.user.entity.User;
import com.mopl.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FollowService {

    private static final String UNKNOWN_USER_NAME = "알 수 없는 사용자";

    private final FollowRepository followRepository;
    private final UserRepository userRepository;
    private final OutboxRecorder outboxRecorder;
    private final FollowEventFactory followEventFactory;

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
                if (retryInserted == 1) {
                    recordFollowCreatedEvent(refetched);
                }
                return new FollowResult(FollowDto.from(refetched), retryInserted == 1);
            }
            // inserted=1 인데 조회가 비어 있으면 방금 넣은 것이 사라진 셈이라
            // 재시도로도 해결되지 않는 인프라 이상. 그대로 500.
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }

        Follow follow = found.get();
        if (inserted == 1) {
            recordFollowCreatedEvent(follow);
        }
        return new FollowResult(FollowDto.from(follow), inserted == 1);
    }

    // 계약 docs/07-kafka-outbox-contract.md §8.1
    // - envelope 조립은 FollowEventFactory 가 담당
    // - partitionKey: followId, orderingScope: NONE
    // - deduplicationKey: follow.created:<followId>
    private void recordFollowCreatedEvent(Follow follow) {
        EventEnvelope envelope = followEventFactory.createFollowCreatedEnvelope(follow);
        KafkaEventContract contract = KafkaEventContract.FOLLOW_CREATED;
        outboxRecorder.record(
                envelope,
                contract.partitionKey(envelope),
                contract.orderingScope(),
                contract.deduplicationKey(envelope));
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
     * 친구의 친구(FoF) 기반 팔로우 추천.
     * <p>Repository 결과의 사용자 ID 를 배치 조회로 UserSummary 채우고 무한 스크롤 방식으로 반환한다.
     * totalCount 는 정확 집계 비용이 크고 UI 요구가 무한 스크롤이라 현재 페이지 크기로 대체한다.
     */
    public CursorResponse<FollowRecommendationItemDto> getRecommendations(
            UUID requesterId, String cursor, UUID idAfter,
            int limit, String sortBy, String sortDirection) {

        if ((cursor != null) != (idAfter != null)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        Long cursorCount;
        try {
            cursorCount = (cursor != null) ? CursorUtils.decodeAsLong(cursor) : null;
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        int fetchSize = limit + 1;
        List<FollowRecommendationRow> rows = followRepository.findRecommendations(
                requesterId.toString(),
                cursorCount,
                idAfter != null ? idAfter.toString() : null,
                fetchSize);

        boolean hasNext = rows.size() == fetchSize;
        List<FollowRecommendationRow> page = hasNext ? rows.subList(0, limit) : rows;

        String nextCursor = null;
        UUID   nextIdAfter = null;
        if (hasNext && !page.isEmpty()) {
            FollowRecommendationRow last = page.get(page.size() - 1);
            nextCursor  = CursorUtils.encodeLong(last.getCommonCount());
            nextIdAfter = last.getUserId();
        }

        Set<UUID> userIds = page.stream()
                .map(FollowRecommendationRow::getUserId)
                .collect(Collectors.toSet());
        Map<UUID, UserSummary> usersById = toUserSummaryMap(userIds);

        List<FollowRecommendationItemDto> data = page.stream()
                .map(r -> new FollowRecommendationItemDto(
                        usersById.getOrDefault(r.getUserId(), unknownUserSummary(r.getUserId())),
                        r.getCommonCount()))
                .toList();

        return CursorResponse.of(data, nextCursor, nextIdAfter, hasNext,
                data.size(), sortBy, sortDirection);
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

        // 페이지 내 user ID 중복 제거는 Set 로 확보한다 (page 순서는 하단에서 별도 유지).
        Set<UUID> userIds = page.stream().map(userIdExtractor).collect(Collectors.toSet());
        Map<UUID, UserSummary> usersById = toUserSummaryMap(userIds);

        List<FollowUserItemDto> data = page.stream()
                .map(f -> {
                    UUID userId = userIdExtractor.apply(f);
                    return new FollowUserItemDto(
                            f.getId(),
                            usersById.getOrDefault(userId, unknownUserSummary(userId)),
                            f.getCreatedAt());
                })
                .toList();

        return CursorResponse.of(data, nextCursor, nextIdAfter, hasNext,
                totalCounter.getAsLong(), sortBy, sortDirection);
    }

    // 페이지 user ID 배치 조회로 N+1 을 방지한다. userRepository.findAllById 1회.
    private Map<UUID, UserSummary> toUserSummaryMap(Collection<UUID> userIds) {
        if (userIds.isEmpty()) return Map.of();
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId,
                        u -> new UserSummary(u.getId(), u.getName(), u.getProfileImageUrl())));
    }

    private UserSummary unknownUserSummary(UUID userId) {
        return new UserSummary(userId, UNKNOWN_USER_NAME, null);
    }
}
