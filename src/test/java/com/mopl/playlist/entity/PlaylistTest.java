package com.mopl.playlist.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlaylistTest {

    private static final UUID OWNER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    @DisplayName("빌더로 생성하면 ownerId·title·description 이 설정되고 subscriberCount 는 0 이다")
    void builder_setsFields() {
        Playlist playlist = Playlist.builder()
                .ownerId(OWNER_ID)
                .title("내 플레이리스트")
                .description("설명입니다")
                .build();

        assertThat(playlist.getOwnerId()).isEqualTo(OWNER_ID);
        assertThat(playlist.getTitle()).isEqualTo("내 플레이리스트");
        assertThat(playlist.getDescription()).isEqualTo("설명입니다");
        assertThat(playlist.getSubscriberCount()).isZero();
    }

    @Test
    @DisplayName("update 호출 시 null 이 아닌 필드만 변경된다")
    void update_onlyNonNullFieldsChanged() {
        Playlist playlist = Playlist.builder()
                .ownerId(OWNER_ID).title("원래 제목").description("원래 설명").build();

        playlist.update("새 제목", null);

        assertThat(playlist.getTitle()).isEqualTo("새 제목");
        assertThat(playlist.getDescription()).isEqualTo("원래 설명");
    }

    @Test
    @DisplayName("isOwnedBy 는 소유자 ID 일 때 true 를 반환한다")
    void isOwnedBy_returnsTrue_forOwner() {
        Playlist playlist = Playlist.builder()
                .ownerId(OWNER_ID).title("제목").description("설명").build();

        assertThat(playlist.isOwnedBy(OWNER_ID)).isTrue();
    }

    @Test
    @DisplayName("isOwnedBy 는 다른 사용자 ID 일 때 false 를 반환한다")
    void isOwnedBy_returnsFalse_forOther() {
        Playlist playlist = Playlist.builder()
                .ownerId(OWNER_ID).title("제목").description("설명").build();

        assertThat(playlist.isOwnedBy(OTHER_ID)).isFalse();
    }

    @Test
    @DisplayName("incrementSubscriberCount 호출 시 subscriberCount 가 1 증가한다")
    void increment_increasesCount() {
        Playlist playlist = Playlist.builder()
                .ownerId(OWNER_ID).title("제목").description("설명").build();

        playlist.incrementSubscriberCount();

        assertThat(playlist.getSubscriberCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("decrementSubscriberCount 호출 시 subscriberCount 가 1 감소한다")
    void decrement_decreasesCount() {
        Playlist playlist = Playlist.builder()
                .ownerId(OWNER_ID).title("제목").description("설명").build();
        playlist.incrementSubscriberCount();

        playlist.decrementSubscriberCount();

        assertThat(playlist.getSubscriberCount()).isZero();
    }

    @Test
    @DisplayName("decrementSubscriberCount 는 0 미만으로 내려가지 않는다")
    void decrement_doesNotGoBelowZero() {
        Playlist playlist = Playlist.builder()
                .ownerId(OWNER_ID).title("제목").description("설명").build();

        playlist.decrementSubscriberCount();

        assertThat(playlist.getSubscriberCount()).isZero();
    }
}