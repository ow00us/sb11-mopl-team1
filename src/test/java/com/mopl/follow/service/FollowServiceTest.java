package com.mopl.follow.service;

import com.mopl.follow.dto.FollowDto;
import com.mopl.follow.entity.Follow;
import com.mopl.follow.repository.FollowRepository;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    @DisplayName("신규 팔로우 성공 시 created=true 와 FollowDto 를 반환한다")
    void follow_success_new() {
        when(userRepository.existsById(FOLLOWEE_ID)).thenReturn(true);
        when(followRepository.existsByFollowerIdAndFolloweeId(FOLLOWER_ID, FOLLOWEE_ID)).thenReturn(false);
        when(followRepository.saveAndFlush(any(Follow.class))).thenAnswer(inv -> {
            Follow f = inv.getArgument(0);
            ReflectionTestUtils.setField(f, "id", FOLLOW_ID);
            return f;
        });

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
    @DisplayName("중복 팔로우 시 예외 대신 created=false 와 기존 FollowDto 를 반환한다 (ADR 2)")
    void follow_duplicate_returnsExistingWithCreatedFalse() {
        Follow existing = savedFollow(FOLLOW_ID, FOLLOWER_ID, FOLLOWEE_ID);
        when(userRepository.existsById(FOLLOWEE_ID)).thenReturn(true);
        when(followRepository.existsByFollowerIdAndFolloweeId(FOLLOWER_ID, FOLLOWEE_ID)).thenReturn(true);
        when(followRepository.findByFollowerIdAndFolloweeId(FOLLOWER_ID, FOLLOWEE_ID))
                .thenReturn(Optional.of(existing));

        FollowResult result = followService.follow(FOLLOWER_ID, FOLLOWEE_ID);

        assertThat(result.created()).isFalse();
        assertThat(result.dto().id()).isEqualTo(FOLLOW_ID);
        verify(followRepository, never()).saveAndFlush(any(Follow.class));
    }

    @Test
    @DisplayName("동시 요청으로 DB 유니크 제약 위반 시 예외 대신 created=false 와 기존 FollowDto 를 반환한다 (ADR 2)")
    void follow_concurrentDuplicate_returnsExistingWithCreatedFalse() {
        Follow existing = savedFollow(FOLLOW_ID, FOLLOWER_ID, FOLLOWEE_ID);
        when(userRepository.existsById(FOLLOWEE_ID)).thenReturn(true);
        when(followRepository.existsByFollowerIdAndFolloweeId(FOLLOWER_ID, FOLLOWEE_ID))
                .thenReturn(false);  // 사전 중복 체크
        when(followRepository.saveAndFlush(any(Follow.class)))
                .thenThrow(new DataIntegrityViolationException("uk_follows_follower_followee"));
        when(followRepository.findByFollowerIdAndFolloweeId(FOLLOWER_ID, FOLLOWEE_ID))
                .thenReturn(Optional.of(existing));

        FollowResult result = followService.follow(FOLLOWER_ID, FOLLOWEE_ID);

        assertThat(result.created()).isFalse();
        assertThat(result.dto().id()).isEqualTo(FOLLOW_ID);
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

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private Follow savedFollow(UUID id, UUID followerId, UUID followeeId) {
        Follow f = Follow.builder().followerId(followerId).followeeId(followeeId).build();
        ReflectionTestUtils.setField(f, "id", id);
        return f;
    }
}
