package com.mopl.follow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.follow.dto.FollowDto;
import com.mopl.follow.dto.FollowRecommendationItemDto;
import com.mopl.follow.dto.FollowUserItemDto;
import com.mopl.follow.entity.Follow;
import com.mopl.follow.repository.FollowRecommendationRow;
import com.mopl.follow.repository.FollowRepository;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.outbox.OutboxRecorder;
import com.mopl.global.util.CursorUtils;
import com.mopl.user.entity.User;
import com.mopl.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FollowServiceTest {

    @Mock FollowRepository followRepository;
    @Mock UserRepository userRepository;
    @Mock OutboxRecorder outboxRecorder;
    @Spy  ObjectMapper objectMapper = new ObjectMapper();
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

    // 계약 docs/07-kafka-outbox-contract.md §8.1: follow.created 는 FollowService.follow() 가
    // 최종적으로 신규 팔로우 생성으로 판정한 경우에만 Outbox 기록한다. 여기서는 호출 여부만
    // 검증하고 envelope 필드 정확성은 후속 Envelope 커밋에서 다룬다.

    @Test
    @DisplayName("신규 팔로우 판정 시 OutboxRecorder.record 를 1회 호출한다")
    void follow_success_new_recordsOutboxOnce() {
        Follow saved = savedFollow(FOLLOW_ID, FOLLOWER_ID, FOLLOWEE_ID);
        when(userRepository.existsById(FOLLOWEE_ID)).thenReturn(true);
        when(followRepository.insertIfAbsent(FOLLOWER_ID.toString(), FOLLOWEE_ID.toString()))
                .thenReturn(1);
        when(followRepository.findByFollowerIdAndFolloweeId(FOLLOWER_ID, FOLLOWEE_ID))
                .thenReturn(Optional.of(saved));

        followService.follow(FOLLOWER_ID, FOLLOWEE_ID);

        verify(outboxRecorder, times(1)).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName("중복 팔로우 판정 시 OutboxRecorder.record 를 호출하지 않는다")
    void follow_duplicate_doesNotRecordOutbox() {
        Follow existing = savedFollow(FOLLOW_ID, FOLLOWER_ID, FOLLOWEE_ID);
        when(userRepository.existsById(FOLLOWEE_ID)).thenReturn(true);
        when(followRepository.insertIfAbsent(FOLLOWER_ID.toString(), FOLLOWEE_ID.toString()))
                .thenReturn(0);
        when(followRepository.findByFollowerIdAndFolloweeId(FOLLOWER_ID, FOLLOWEE_ID))
                .thenReturn(Optional.of(existing));

        followService.follow(FOLLOWER_ID, FOLLOWEE_ID);

        verify(outboxRecorder, never()).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName("동시 unfollow race 재시도로 신규 삽입 성공 시 OutboxRecorder.record 를 1회 호출한다")
    void follow_raceRetryInserted_recordsOutboxOnce() {
        Follow reinserted = savedFollow(FOLLOW_ID, FOLLOWER_ID, FOLLOWEE_ID);
        when(userRepository.existsById(FOLLOWEE_ID)).thenReturn(true);
        when(followRepository.insertIfAbsent(FOLLOWER_ID.toString(), FOLLOWEE_ID.toString()))
                .thenReturn(0)
                .thenReturn(1);
        when(followRepository.findByFollowerIdAndFolloweeId(FOLLOWER_ID, FOLLOWEE_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(reinserted));

        followService.follow(FOLLOWER_ID, FOLLOWEE_ID);

        verify(outboxRecorder, times(1)).record(any(), any(), any(), any());
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

    @Test
    @DisplayName("getFollowers 는 페이지 follower ID 를 배치 조회해 user.name/profileImageUrl 을 채운다")
    void getFollowers_populatesUserNameAndProfileImageUrl() {
        UUID follower1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID follower2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Follow f1 = savedFollowWithCreatedAt(UUID.randomUUID(), follower1, FOLLOWEE_ID,
                Instant.parse("2026-08-01T11:00:00Z"));
        Follow f2 = savedFollowWithCreatedAt(UUID.randomUUID(), follower2, FOLLOWEE_ID,
                Instant.parse("2026-08-01T10:00:00Z"));
        User u1 = savedUser(follower1, "userA", "https://cdn/a.png");
        User u2 = savedUser(follower2, "userB", "https://cdn/b.png");

        when(followRepository.findFollowersByFolloweeIdDesc(eq(FOLLOWEE_ID.toString()), any(), any(), eq(11)))
                .thenReturn(List.of(f1, f2));
        when(followRepository.countByFolloweeId(FOLLOWEE_ID)).thenReturn(2L);
        when(userRepository.findAllById(any())).thenReturn(List.of(u1, u2));

        CursorResponse<FollowUserItemDto> result = followService.getFollowers(
                FOLLOWEE_ID, null, null, 10, "followedAt", "DESCENDING");

        assertThat(result.data()).hasSize(2);
        assertThat(result.data().get(0).user().name()).isEqualTo("userA");
        assertThat(result.data().get(0).user().profileImageUrl()).isEqualTo("https://cdn/a.png");
        assertThat(result.data().get(1).user().name()).isEqualTo("userB");
        assertThat(result.data().get(1).user().profileImageUrl()).isEqualTo("https://cdn/b.png");
        // N+1 방지 검증: follower 수와 무관하게 findAllById 1회 호출
        verify(userRepository).findAllById(any());
    }

    @Test
    @DisplayName("getFollowers 는 user 조회 결과에 없는 follower 에 대해 UNKNOWN fallback 을 반환한다")
    void getFollowers_fallbackToUnknownWhenUserMissing() {
        UUID follower1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        Follow f1 = savedFollowWithCreatedAt(UUID.randomUUID(), follower1, FOLLOWEE_ID,
                Instant.parse("2026-08-01T11:00:00Z"));

        when(followRepository.findFollowersByFolloweeIdDesc(eq(FOLLOWEE_ID.toString()), any(), any(), eq(11)))
                .thenReturn(List.of(f1));
        when(followRepository.countByFolloweeId(FOLLOWEE_ID)).thenReturn(1L);
        when(userRepository.findAllById(any())).thenReturn(List.of());

        CursorResponse<FollowUserItemDto> result = followService.getFollowers(
                FOLLOWEE_ID, null, null, 10, "followedAt", "DESCENDING");

        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).user().userId()).isEqualTo(follower1);
        assertThat(result.data().get(0).user().name()).isEqualTo("알 수 없는 사용자");
        assertThat(result.data().get(0).user().profileImageUrl()).isNull();
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

    @Test
    @DisplayName("getFollowings 는 페이지 followee ID 를 배치 조회해 user.name/profileImageUrl 을 채운다")
    void getFollowings_populatesUserNameAndProfileImageUrl() {
        UUID followee1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID followee2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Follow f1 = savedFollowWithCreatedAt(UUID.randomUUID(), FOLLOWER_ID, followee1,
                Instant.parse("2026-08-01T11:00:00Z"));
        Follow f2 = savedFollowWithCreatedAt(UUID.randomUUID(), FOLLOWER_ID, followee2,
                Instant.parse("2026-08-01T10:00:00Z"));
        User u1 = savedUser(followee1, "userA", "https://cdn/a.png");
        User u2 = savedUser(followee2, "userB", "https://cdn/b.png");

        when(followRepository.findFollowingsByFollowerIdDesc(eq(FOLLOWER_ID.toString()), any(), any(), eq(11)))
                .thenReturn(List.of(f1, f2));
        when(followRepository.countByFollowerId(FOLLOWER_ID)).thenReturn(2L);
        when(userRepository.findAllById(any())).thenReturn(List.of(u1, u2));

        CursorResponse<FollowUserItemDto> result = followService.getFollowings(
                FOLLOWER_ID, null, null, 10, "followedAt", "DESCENDING");

        assertThat(result.data()).hasSize(2);
        assertThat(result.data().get(0).user().name()).isEqualTo("userA");
        assertThat(result.data().get(0).user().profileImageUrl()).isEqualTo("https://cdn/a.png");
        assertThat(result.data().get(1).user().name()).isEqualTo("userB");
        assertThat(result.data().get(1).user().profileImageUrl()).isEqualTo("https://cdn/b.png");
        verify(userRepository).findAllById(any());
    }

    // ── Phase E: 남은 조건 분기 커버 ─────────────────────────────────────

    @Test
    @DisplayName("insertIfAbsent=1 인데 findBy 가 empty (이례적) 이면 즉시 INTERNAL_ERROR 를 던진다")
    void follow_insertedButNotFound_throwsInternalErrorWithoutRetry() {
        when(userRepository.existsById(FOLLOWEE_ID)).thenReturn(true);
        when(followRepository.insertIfAbsent(FOLLOWER_ID.toString(), FOLLOWEE_ID.toString()))
                .thenReturn(1);
        when(followRepository.findByFollowerIdAndFolloweeId(FOLLOWER_ID, FOLLOWEE_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> followService.follow(FOLLOWER_ID, FOLLOWEE_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INTERNAL_ERROR);

        // 재시도 없이 즉시 실패해야 함
        verify(followRepository, times(1))
                .insertIfAbsent(FOLLOWER_ID.toString(), FOLLOWEE_ID.toString());
    }

    @Test
    @DisplayName("race: 재시도에서 다른 tx 가 이미 삽입해 retryInserted=0 이지만 refetched 가 존재하면 created=false 로 정상 반환")
    void follow_retryRowsZeroButRefetchFound_returnsCreatedFalse() {
        Follow existing = savedFollow(FOLLOW_ID, FOLLOWER_ID, FOLLOWEE_ID);
        when(userRepository.existsById(FOLLOWEE_ID)).thenReturn(true);
        when(followRepository.insertIfAbsent(FOLLOWER_ID.toString(), FOLLOWEE_ID.toString()))
                .thenReturn(0)
                .thenReturn(0);
        when(followRepository.findByFollowerIdAndFolloweeId(FOLLOWER_ID, FOLLOWEE_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));

        FollowResult result = followService.follow(FOLLOWER_ID, FOLLOWEE_ID);

        assertThat(result.created()).isFalse();
        assertThat(result.dto().id()).isEqualTo(FOLLOW_ID);
    }

    @Test
    @DisplayName("getFollowers 는 cursor + idAfter 로 호출되면 pageFetcher lambda 가 idAfter 를 문자열로 넘긴다")
    void getFollowers_withCursorAndIdAfter_passesIdAfterAsString() {
        UUID idAfter = UUID.randomUUID();
        String cursor = CursorUtils.encodeInstant(Instant.parse("2026-08-01T10:00:00Z"));

        when(followRepository.findFollowersByFolloweeIdDesc(
                eq(FOLLOWEE_ID.toString()), any(), eq(idAfter.toString()), eq(11)))
                .thenReturn(List.of());
        when(followRepository.countByFolloweeId(FOLLOWEE_ID)).thenReturn(0L);

        followService.getFollowers(FOLLOWEE_ID, cursor, idAfter, 10, "followedAt", "DESCENDING");

        verify(followRepository).findFollowersByFolloweeIdDesc(
                eq(FOLLOWEE_ID.toString()), any(), eq(idAfter.toString()), eq(11));
    }

    @Test
    @DisplayName("getFollowings 는 cursor + idAfter 로 호출되면 pageFetcher lambda 가 idAfter 를 문자열로 넘긴다")
    void getFollowings_withCursorAndIdAfter_passesIdAfterAsString() {
        UUID idAfter = UUID.randomUUID();
        String cursor = CursorUtils.encodeInstant(Instant.parse("2026-08-01T10:00:00Z"));

        when(followRepository.findFollowingsByFollowerIdDesc(
                eq(FOLLOWER_ID.toString()), any(), eq(idAfter.toString()), eq(11)))
                .thenReturn(List.of());
        when(followRepository.countByFollowerId(FOLLOWER_ID)).thenReturn(0L);

        followService.getFollowings(FOLLOWER_ID, cursor, idAfter, 10, "followedAt", "DESCENDING");

        verify(followRepository).findFollowingsByFollowerIdDesc(
                eq(FOLLOWER_ID.toString()), any(), eq(idAfter.toString()), eq(11));
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    // ── getRecommendations ────────────────────────────────────────────────────

    @Test
    @DisplayName("팔로우 추천은 페이지 결과에 UserSummary 를 배치 조회로 채워 반환한다")
    void getRecommendations_success_returnsPageWithUserSummary() {
        UUID candidate1 = UUID.randomUUID();
        UUID candidate2 = UUID.randomUUID();

        when(followRepository.findRecommendations(
                eq(FOLLOWER_ID.toString()), eq(null), eq(null), eq(11)))
                .thenReturn(List.of(
                        recommendationRow(candidate1, 3L),
                        recommendationRow(candidate2, 1L)));
        when(userRepository.findAllById(any())).thenReturn(List.of(
                savedUser(candidate1, "이름A", "https://cdn/a.png"),
                savedUser(candidate2, "이름B", "https://cdn/b.png")));

        CursorResponse<FollowRecommendationItemDto> result = followService.getRecommendations(
                FOLLOWER_ID, null, null, 10, "commonFollowingCount", "DESCENDING");

        assertThat(result.data()).hasSize(2);
        assertThat(result.data().get(0).user().userId()).isEqualTo(candidate1);
        assertThat(result.data().get(0).user().name()).isEqualTo("이름A");
        assertThat(result.data().get(0).commonFollowingCount()).isEqualTo(3L);
        assertThat(result.data().get(1).commonFollowingCount()).isEqualTo(1L);
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    @DisplayName("추천 조회에서 cursor 와 idAfter 중 하나만 있으면 INVALID_INPUT 예외가 발생한다")
    void getRecommendations_orphanCursorPair_throws400() {
        assertThatThrownBy(() -> followService.getRecommendations(
                FOLLOWER_ID, CursorUtils.encodeLong(3L), null, 10, "commonFollowingCount", "DESCENDING"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        assertThatThrownBy(() -> followService.getRecommendations(
                FOLLOWER_ID, null, UUID.randomUUID(), 10, "commonFollowingCount", "DESCENDING"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verifyNoInteractions(followRepository);
    }

    @Test
    @DisplayName("추천 조회에서 잘못된 cursor 형식은 INVALID_INPUT 예외가 발생한다")
    void getRecommendations_invalidCursorFormat_throws400() {
        assertThatThrownBy(() -> followService.getRecommendations(
                FOLLOWER_ID, "not-a-valid-base64-long", UUID.randomUUID(),
                10, "commonFollowingCount", "DESCENDING"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verifyNoInteractions(followRepository);
    }

    @Test
    @DisplayName("추천 결과의 사용자가 조회되지 않으면 UNKNOWN fallback 을 채워 반환한다")
    void getRecommendations_fallbackToUnknownWhenUserMissing() {
        UUID candidate = UUID.randomUUID();
        when(followRepository.findRecommendations(any(), any(), any(), eq(11)))
                .thenReturn(List.of(recommendationRow(candidate, 2L)));
        when(userRepository.findAllById(any())).thenReturn(List.of());

        CursorResponse<FollowRecommendationItemDto> result = followService.getRecommendations(
                FOLLOWER_ID, null, null, 10, "commonFollowingCount", "DESCENDING");

        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).user().userId()).isEqualTo(candidate);
        assertThat(result.data().get(0).user().name()).isEqualTo("알 수 없는 사용자");
        assertThat(result.data().get(0).user().profileImageUrl()).isNull();
    }

    @Test
    @DisplayName("추천 조회에서 fetchSize 를 넘어서면 hasNext=true 와 다음 커서를 반환한다")
    void getRecommendations_hasNext_setsNextCursor() {
        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();
        UUID c3 = UUID.randomUUID();

        // limit=2 → fetchSize=3. Repository 가 3건 반환 → hasNext=true, page 는 앞 2건.
        when(followRepository.findRecommendations(
                eq(FOLLOWER_ID.toString()), eq(null), eq(null), eq(3)))
                .thenReturn(List.of(
                        recommendationRow(c1, 5L),
                        recommendationRow(c2, 3L),
                        recommendationRow(c3, 1L)));
        when(userRepository.findAllById(any())).thenReturn(List.of(
                savedUser(c1, "A", null),
                savedUser(c2, "B", null)));

        CursorResponse<FollowRecommendationItemDto> result = followService.getRecommendations(
                FOLLOWER_ID, null, null, 2, "commonFollowingCount", "DESCENDING");

        assertThat(result.data()).hasSize(2);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.nextCursor()).isEqualTo(CursorUtils.encodeLong(3L));
        assertThat(result.nextIdAfter()).isEqualTo(c2);
    }

    private FollowRecommendationRow recommendationRow(UUID userId, long commonCount) {
        return new FollowRecommendationRow() {
            @Override public UUID getUserId() { return userId; }
            @Override public long getCommonCount() { return commonCount; }
        };
    }

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

    private User savedUser(UUID id, String name, String profileImageUrl) {
        User u = User.builder()
                .email(id + "@example.com")
                .passwordHash("hash")
                .name(name)
                .profileImageUrl(profileImageUrl)
                .build();
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }
}
