package com.mopl.content.external.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TmdbGenreMapperTest {

    @Test
    @DisplayName("알려진 영화 장르 ID는 장르명을 반환한다")
    void movieGenreName_knownId_returnsName() {
        assertThat(TmdbGenreMapper.movieGenreName(28)).isEqualTo("Action");
        assertThat(TmdbGenreMapper.movieGenreName(12)).isEqualTo("Adventure");
        assertThat(TmdbGenreMapper.movieGenreName(18)).isEqualTo("Drama");
    }

    @Test
    @DisplayName("매핑에 없는 영화 장르 ID는 null을 반환한다")
    void movieGenreName_unknownId_returnsNull() {
        assertThat(TmdbGenreMapper.movieGenreName(999999)).isNull();
    }

    @Test
    @DisplayName("알려진 TV 장르 ID는 장르명을 반환한다")
    void tvGenreName_knownId_returnsName() {
        assertThat(TmdbGenreMapper.tvGenreName(10759)).isEqualTo("Action & Adventure");
        assertThat(TmdbGenreMapper.tvGenreName(35)).isEqualTo("Comedy");
    }

    @Test
    @DisplayName("매핑에 없는 TV 장르 ID는 null을 반환한다")
    void tvGenreName_unknownId_returnsNull() {
        assertThat(TmdbGenreMapper.tvGenreName(999999)).isNull();
    }
}