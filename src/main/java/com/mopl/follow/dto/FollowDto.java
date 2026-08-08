package com.mopl.follow.dto;

import com.mopl.follow.entity.Follow;

import java.util.UUID;

public record FollowDto(UUID id, UUID followeeId, UUID followerId) {

    public static FollowDto from(Follow follow) {
        return new FollowDto(follow.getId(), follow.getFolloweeId(), follow.getFollowerId());
    }
}