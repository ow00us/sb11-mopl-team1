package com.mopl.content.external.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.content.entity.ContentSource;
import com.mopl.content.entity.ContentType;
import com.mopl.content.external.tmdb.TmdbProperties;
import com.mopl.content.external.tmdb.dto.TmdbMovieSummary;
import com.mopl.content.external.tmdb.dto.TmdbTvSummary;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TmdbContentMapperTest {

    private final TmdbContentMapper mapper = new TmdbContentMapper(
            new TmdbProperties("base", "token", "https://image.tmdb.org/t/p/w500"));

    @Test
    @DisplayName("영화 응답의 모든 필드가 draft에 매핑된다")
    void toDraft_movie_mapsAllFields() {
        TmdbMovieSummary movie = new TmdbMovieSummary(1L, "제목", "줄거리", "/poster.jpg", List.of(28, 12));

        ExternalContentDraft draft = mapper.toDraft(movie);

        assertThat(draft.type()).isEqualTo(ContentType.MOVIE);
        assertThat(draft.source()).isEqualTo(ContentSource.TMDB);
        assertThat(draft.externalId()).isEqualTo("1");
        assertThat(draft.title()).isEqualTo("제목");
        assertThat(draft.description()).isEqualTo("줄거리");
        assertThat(draft.thumbnailUrl()).isEqualTo("https://image.tmdb.org/t/p/w500/poster.jpg");
        assertThat(draft.tags()).contains("Action", "Adventure");
    }

    @Test
    @DisplayName("overview가 null이면 description은 빈 문자열로 매핑된다")
    void toDraft_movie_nullOverview_mapsToEmptyString() {
        TmdbMovieSummary movie = new TmdbMovieSummary(1L, "제목", null, "/poster.jpg", List.of(28));

        ExternalContentDraft draft = mapper.toDraft(movie);

        assertThat(draft.description()).isEmpty();
    }

    @Test
    @DisplayName("posterPath가 null이면 thumbnailUrl은 null로 매핑된다")
    void toDraft_movie_nullPosterPath_mapsToNullThumbnail() {
        TmdbMovieSummary movie = new TmdbMovieSummary(1L, "제목", "줄거리", null, List.of(28));

        ExternalContentDraft draft = mapper.toDraft(movie);

        assertThat(draft.thumbnailUrl()).isNull();
    }

    @Test
    @DisplayName("매핑되지 않는 장르 ID는 태그에서 제외되고 나머지는 정상 포함된다")
    void toDraft_movie_unknownGenreId_filteredOut() {
        TmdbMovieSummary movie = new TmdbMovieSummary(1L, "제목", "줄거리", "/poster.jpg", List.of(28, 999999));

        ExternalContentDraft draft = mapper.toDraft(movie);

        assertThat(draft.tags()).containsExactly("Action");
    }

    @Test
    @DisplayName("genreIds가 null이면 빈 태그 Set을 반환한다")
    void toDraft_movie_nullGenreIds_returnsEmptyTags() {
        TmdbMovieSummary movie = new TmdbMovieSummary(1L, "제목", "줄거리", "/poster.jpg", null);

        ExternalContentDraft draft = mapper.toDraft(movie);

        assertThat(draft.tags()).isEmpty();
    }

    @Test
    @DisplayName("TV 응답의 모든 필드가 draft에 매핑된다")
    void toDraft_tv_mapsAllFields() {
        TmdbTvSummary tv = new TmdbTvSummary(2L, "TV 제목", "TV 줄거리", "/tv-poster.jpg", List.of(35));

        ExternalContentDraft draft = mapper.toDraft(tv);

        assertThat(draft.type()).isEqualTo(ContentType.TV_SERIES);
        assertThat(draft.source()).isEqualTo(ContentSource.TMDB);
        assertThat(draft.externalId()).isEqualTo("2");
        assertThat(draft.title()).isEqualTo("TV 제목");
        assertThat(draft.description()).isEqualTo("TV 줄거리");
        assertThat(draft.thumbnailUrl()).isEqualTo("https://image.tmdb.org/t/p/w500/tv-poster.jpg");
        assertThat(draft.tags()).contains("Comedy");
    }
}