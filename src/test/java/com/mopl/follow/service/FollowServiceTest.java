package com.mopl.follow.service;

import com.mopl.follow.dto.FollowDto;
import com.mopl.follow.dto.FollowUserItemDto;
import com.mopl.follow.entity.Follow;
import com.mopl.follow.repository.FollowRepository;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.util.CursorUtils;
import com.mopl.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FollowServiceTest {

    @Mock FollowRepository followRepository;
    @Mock UserRepository userRepository;
    @InjectMocks FollowService followService;

    private static final UUID FOLLOWER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID FOLLOWEE_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID FOLLOW_ID   = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    // ── follow ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("신규 팔로우 성공 시 created=true 와 FollowDto 를 반환한다 (upsert rows=1)")
    void follow_success_new() {
        Follow saved = savedFollow(FOLLOW_ID, FOLLOWER_ID, FOLLOWEE_ID);
        when(userRepository.existsById(FOLLOWEE_ID)).thenReturn(true);
        when(followRepository.insertIfAbsent(FOLLOWER_ID.toString(), FOLLOWEE_ID.toString()))
                .thenReturn(1);
        when(followRepository.findByFollowerIdAndFolloweeId(FOLLOWER_ID, FOLLOWEE_ID))
                .thenReturn(Optional.of(saved));

        FollowResult result = followService.follow(FOLLOWER_ID, FOLLOWEE_ID);

        assertThat(result.created()).isTrue();
        assertThat(result.dto().id()).isEqualTo(FOLLOW_ID);
        assertThat(result.dto().followerId()).isEqualTo(FOLLOWER_ID);
        assertThat(result.dto().followeeId()).isEqualTo(FOLLOWEE_ID);
    }

    @Test
    @DisplayName("자기 자신 팔로우 시 FOLLOW_SELF 예외가 발생한다")
    void follow_fail_self() {
        assertThatThrownBy(() -> followService.follow(FOLLOWER_ID, FOLLOWER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FOLLOW_SELF);

        verifyNoInteractions(followRepository);
    }

    @Test
    @DisplayName("존재하지 않는 사용자를 팔로우하면 RESOURCE_NOT_FOUND 예외가 발생한다")
    void follow_fail_followeeNotFound() {
        when(userRepository.existsById(FOLLOWEE_ID)).thenReturn(false);

        assertThatThrownBy(() -> followService.follow(FOLLOWER_ID, FOLLOWEE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        verifyNoInteractions(followRepository);
    }

    @Test
    @DisplayName("중복 팔로우 시 upsert rows=0, created=false 와 기존 FollowDto 를 반환한다 (ADR 2)")
    void follow_duplicate_returnsExistingWithCreatedFalse() {
        Follow existing = savedFollow(FOLLOW_ID, FOLLOWER_ID, FOLLOWEE_ID);
        when(userRepository.existsById(FOLLOWEE_ID)).thenReturn(true);
        when(followRepository.insertIfAbsent(FOLLOWER_ID.toString(), FOLLOWEE_ID.toString()))
                .thenReturn(0);
        when(followRepository.findByFollowerIdAndFolloweeId(FOLLOWER_ID, FOLLOWEE_ID))
                .thenReturn(Optional.of(existing));

        FollowResult result = followService.follow(FOLLOWER_ID, FOLLOWEE_ID);

        assertThat(result.created()).isFalse();
        assertThat(result.dto().id()).isEqualTo(FOLLOW_ID);
    }

    @Test
    @DisplayName("insertIfAbsent=0 이후 findBy 가 empty (동시 unfollow race) 이면 재시도해 신규 관계를 반환한다")
    void follow_raceUnfollowedBetweenUpsertAndLookup_retriesAndSucceeds() {
        Follow reinserted = savedFollow(FOLLOW_ID, FOLLOWER_ID, FOLLOWEE_ID);
        when(userRepository.existsById(FOLLOWEE_ID)).thenReturn(true);
        // 1차 upsert: 이미 존재하는 것으로 판단(rows=0) → 재조회가 그러나 empty (그 사이 다른 tx 가 unfollow)
        // 2차 upsert: 이번엔 신규 삽입 성공(rows=1)
        when(followRepository.insertIfAbsent(FOLLOWER_ID.toString(), FOLLOWEE_ID.toString()))
                .thenReturn(0)
                .thenReturn(1);
        when(followRepository.findByFollowerIdAndFolloweeId(FOLLOWER_ID, FOLLOWEE_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(reinserted));

        FollowResult result = followService.follow(FOLLOWER_ID, FOLLOWEE_ID);

        assertThat(result.created()).isTrue();  // 재시도에서 신규 삽입 성공
        assertThat(result.dto().id()).isEqualTo(FOLLOW_ID);
        verify(followRepository, times(2))
                .insertIfAbsent(FOLLOWER_ID.toString(), FOLLOWEE_ID.toString());
        verify(followRepository, times(2))
                .findByFollowerIdAndFolloweeId(FOLLOWER_ID, FOLLOWEE_ID);
    }

    @Test
    @DisplayName("재시도 후에도 findBy 가 empty 이면 INTERNAL_ERROR (500) 를 던진다")
    void follow_raceRetryStillEmpty_throwsInternalError() {
        when(userRepository.existsById(FOLLOWEE_ID)).thenReturn(true);
        when(followRepository.insertIfAbsent(FOLLOWER_ID.toString(), FOLLOWEE_ID.toString()))
                .thenReturn(0)
                .thenReturn(0);
        when(followRepository.findByFollowerIdAndFolloweeId(FOLLOWER_ID, FOLLOWEE_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> followService.follow(FOLLOWER_ID, FOLLOWEE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    // ── unfollow ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("팔로우 취소 성공 시 정상 삭제된다")
    void unfollow_success() {
        Follow follow = savedFollow(FOLLOW_ID, FOLLOWER_ID, FOLLOWEE_ID);
        when(followRepository.findById(FOLLOW_ID)).thenReturn(Optional.of(follow));

        followService.unfollow(FOLLOW_ID, FOLLOWER_ID);

        verify(followRepository).delete(follow);
    }

    @Test
    @DisplayName("존재하지 않는 팔로우 취소 시 RESOURCE_NOT_FOUND 예외가 발생한다")
    void unfollow_fail_notFound() {
        when(followRepository.findById(FOLLOW_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> followService.unfollow(FOLLOW_ID, FOLLOWER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("본인 팔로우가 아닌 취소 시도 시 FORBIDDEN 예외가 발생한다")
    void unfollow_fail_forbidden() {
        UUID otherId = UUID.randomUUID();
        Follow follow = savedFollow(FOLLOW_ID, otherId, FOLLOWEE_ID);
        when(followRepository.findById(FOLLOW_ID)).thenReturn(Optional.of(follow));

        assertThatThrownBy(() -> followService.unfollow(FOLLOW_ID, FOLLOWER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);

        verify(followRepository, never()).delete(any());
    }

    // ── countFollowers ────────────────────────────────────────────────────────

    @Test
    @DisplayName("팔로워 수를 반환한다")
    void countFollowers_success() {
        when(followRepository.countByFolloweeId(FOLLOWEE_ID)).thenReturn(5L);

        assertThat(followService.countFollowers(FOLLOWEE_ID)).isEqualTo(5L);
    }

    // ── getFollowedByMe ───────────────────────────────────────────────────────

    @Test
    @DisplayName("팔로우 중이면 FollowDto 를 반환한다")
    void getFollowedByMe_success() {
        Follow follow = savedFollow(FOLLOW_ID, FOLLOWER_ID, FOLLOWEE_ID);
        when(followRepository.findByFollowerIdAndFolloweeId(FOLLOWER_ID, FOLLOWEE_ID))
                .thenReturn(Optional.of(follow));

        FollowDto result = followService.getFollowedByMe(FOLLOWER_ID, FOLLOWEE_ID);

        assertThat(result.id()).isEqualTo(FOLLOW_ID);
    }

    @Test
    @DisplayName("팔로우 중이 아니면 RESOURCE_NOT_FOUND 예외가 발생한다")
    void getFollowedByMe_fail_notFollowing() {
        when(followRepository.findByFollowerIdAndFolloweeId(FOLLOWER_ID, FOLLOWEE_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> followService.getFollowedByMe(FOLLOWER_ID, FOLLOWEE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ── getFollowers ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("getFollowers 는 최근순 정렬 결과를 CursorResponse 로 매핑한다 (hasNext=false)")
    void getFollowers_firstPage_noNext() {
        Instant t1 = Instant.parse("2026-08-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-08-01T11:00:00Z");
        UUID follower1 = UUID.randomUUID();
        UUID follower2 = UUID.randomUUID();
        Follow f1 = savedFollowWithCreatedAt(UUID.randomUUID(), follower1, FOLLOWEE_ID, t2);
        Follow f2 = savedFollowWithCreatedAt(UUID.randomUUID(), follower2, FOLLOWEE_ID, t1);

        when(followRepository.findFollowersByFolloweeIdDesc(
                eq(FOLLOWEE_ID.toString()), any(), any(), eq(11)))
                .thenReturn(List.of(f1, f2));
        when(followRepository.countByFolloweeId(FOLLOWEE_ID)).thenReturn(2L);

        CursorResponse<FollowUserItemDto> result = followService.getFollowers(
                FOLLOWEE_ID, null, null, 10, "followedAt", "DESCENDING");

        assertThat(result.data()).hasSize(2);
        assertThat(result.data().get(0).followId()).isEqualTo(f1.getId());
        assertThat(result.data().get(0).user().userId()).isEqualTo(follower1);
        assertThat(result.data().get(0).followedAt()).isEqualTo(t2);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.nextCursor()).isNull();
        assertThat(result.nextIdAfter()).isNull();
        assertThat(result.totalCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("getFollowers 는 결과 수가 limit+1 이면 hasNext=true 와 nextCursor 를 설정한다")
    void getFollowers_hasNext() {
        Instant base = Instant.parse("2026-08-01T10:00:00Z");
        Follow f1 = savedFollowWithCreatedAt(UUID.randomUUID(), UUID.randomUUID(), FOLLOWEE_ID, base.plusSeconds(30));
        Follow f2 = savedFollowWithCreatedAt(UUID.randomUUID(), UUID.randomUUID(), FOLLOWEE_ID, base.plusSeconds(20));
        Follow f3 = savedFollowWithCreatedAt(UUID.randomUUID(), UUID.randomUUID(), FOLLOWEE_ID, base.plusSeconds(10));

        when(followRepository.findFollowersByFolloweeIdDesc(
                eq(FOLLOWEE_ID.toString()), any(), any(), eq(3)))
                .thenReturn(List.of(f1, f2, f3));
        when(followRepository.countByFolloweeId(FOLLOWEE_ID)).thenReturn(5L);

        CursorResponse<FollowUserItemDto> result = followService.getFollowers(
                FOLLOWEE_ID, null, null, 2, "followedAt", "DESCENDING");

        assertThat(result.data()).hasSize(2);
        assertThat(result.hasNext()).isTrue();
        // limit=2 로 잘렸으므로 마지막은 f2
        assertThat(result.nextCursor()).isEqualTo(CursorUtils.encodeInstant(f2.getCreatedAt()));
        assertThat(result.nextIdAfter()).isEqualTo(f2.getId());
        assertThat(result.totalCount()).isEqualTo(5L);
    }

    @Test
    @DisplayName("getFollowers 에 cursor 만 있고 idAfter 가 없으면 INVALID_INPUT 예외가 발생한다")
    void getFollowers_fail_cursorWithoutIdAfter() {
        String validCursor = CursorUtils.encodeInstant(Instant.now());

        assertThatThrownBy(() -> followService.getFollowers(
                FOLLOWEE_ID, validCursor, null, 10, "followedAt", "DESCENDING"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("getFollowers 에 잘못된 cursor 값이 들어오면 INVALID_INPUT 예외가 발생한다")
    void getFollowers_fail_invalidCursor() {
        assertThatThrownBy(() -> followService.getFollowers(
                FOLLOWEE_ID, "not-base64!!", UUID.randomUUID(), 10, "followedAt", "DESCENDING"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    // ── getFollowings ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("getFollowings 는 최근순 정렬 결과를 CursorResponse 로 매핑한다 (hasNext=false)")
    void getFollowings_firstPage_noNext() {
        Instant t1 = Instant.parse("2026-08-01T10:00:00Z");
        UUID followee1 = UUID.randomUUID();
        Follow f1 = savedFollowWithCreatedAt(UUID.randomUUID(), FOLLOWER_ID, followee1, t1);

        when(followRepository.findFollowingsByFollowerIdDesc(
                eq(FOLLOWER_ID.toString()), any(), any(), eq(11)))
                .thenReturn(List.of(f1));
        when(followRepository.countByFollowerId(FOLLOWER_ID)).thenReturn(1L);

        CursorResponse<FollowUserItemDto> result = followService.getFollowings(
                FOLLOWER_ID, null, null, 10, "followedAt", "DESCENDING");

        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).followId()).isEqualTo(f1.getId());
        assertThat(result.data().get(0).user().userId()).isEqualTo(followee1);
        assertThat(result.data().get(0).followedAt()).isEqualTo(t1);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.totalCount()).isEqualTo(1L);
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private Follow savedFollow(UUID id, UUID followerId, UUID followeeId) {
        Follow f = Follow.builder().followerId(followerId).followeeId(followeeId).build();
        ReflectionTestUtils.setField(f, "id", id);
        return f;
    }

    private Follow savedFollowWithCreatedAt(UUID id, UUID followerId, UUID followeeId, Instant createdAt) {
        Follow f = savedFollow(id, followerId, followeeId);
        ReflectionTestUtils.setField(f, "createdAt", createdAt);
        return f;
    }
}
