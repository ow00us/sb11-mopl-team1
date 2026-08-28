package com.mopl.content.external.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentSource;
import com.mopl.content.entity.ContentType;
import com.mopl.content.external.mapping.TmdbContentLocalizationBackfillService.BackfillResult;
import com.mopl.content.external.tmdb.TmdbApiClient;
import com.mopl.content.external.tmdb.TmdbApiException;
import com.mopl.content.external.tmdb.dto.TmdbMovieDetail;
import com.mopl.content.external.tmdb.dto.TmdbTvDetail;
import com.mopl.content.repository.ContentRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TmdbContentLocalizationBackfillServiceTest {

    @Mock
    ContentRepository contentRepository;

    @Mock
    TmdbApiClient tmdbApiClient;

    @Mock
    TmdbContentLocalizer tmdbContentLocalizer;

    @InjectMocks
    TmdbContentLocalizationBackfillService backfillService;

    private Content content(ContentType type, String externalId) {
        Content content = Content.builder()
                .type(type)
                .source(ContentSource.TMDB)
                .externalId(externalId)
                .title("original title")
                .description("original description")
                .build();
        ReflectionTestUtils.setField(content, "id", UUID.randomUUID());
        return content;
    }

    private void mockSingleSlice(Content... contents) {
        when(contentRepository.findBySource(eq(ContentSource.TMDB), any(Pageable.class)))
                .thenReturn(new SliceImpl<>(List.of(contents)));
    }

    @Test
    @DisplayName("MOVIE 콘텐츠는 getMovieDetail을 호출하고 반환된 title·overview로 현지화한다")
    void backfill_movieContent_callsGetMovieDetailAndLocalizes() {
        Content movie = content(ContentType.MOVIE, "603");
        mockSingleSlice(movie);
        when(tmdbApiClient.getMovieDetail("603"))
                .thenReturn(new TmdbMovieDetail(603L, "매트릭스", "줄거리"));

        BackfillResult result = backfillService.backfill();

        verify(tmdbApiClient).getMovieDetail("603");
        verify(tmdbContentLocalizer).localize(movie.getId(), "매트릭스", "줄거리");
        assertThat(result).isEqualTo(new BackfillResult(1, 1, 0));
    }

    @Test
    @DisplayName("TV_SERIES 콘텐츠는 getTvDetail을 호출하고 반환된 name·overview로 현지화한다")
    void backfill_tvContent_callsGetTvDetailAndLocalizes() {
        Content tv = content(ContentType.TV_SERIES, "1399");
        mockSingleSlice(tv);
        when(tmdbApiClient.getTvDetail("1399"))
                .thenReturn(new TmdbTvDetail(1399L, "왕좌의 게임", "줄거리"));

        BackfillResult result = backfillService.backfill();

        verify(tmdbApiClient).getTvDetail("1399");
        verify(tmdbContentLocalizer).localize(tv.getId(), "왕좌의 게임", "줄거리");
        assertThat(result).isEqualTo(new BackfillResult(1, 1, 0));
    }

    @Test
    @DisplayName("SPORT 콘텐츠는 API 호출·현지화 없이 건너뛰고 total에만 포함된다")
    void backfill_sportTypeContent_isSkippedWithoutApiCall() {
        Content sport = content(ContentType.SPORT, "999");
        mockSingleSlice(sport);

        BackfillResult result = backfillService.backfill();

        verifyNoInteractions(tmdbApiClient);
        verify(tmdbContentLocalizer, never()).localize(any(), any(), any());
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.updated()).isZero();
        assertThat(result.failed()).isZero();
    }

    @Test
    @DisplayName("한 건에서 API 예외가 나도 failed로 세고 다음 콘텐츠 처리를 계속한다")
    void backfill_apiCallThrows_countsAsFailedAndContinuesToNextContent() {
        Content first = content(ContentType.MOVIE, "1");
        Content second = content(ContentType.MOVIE, "2");
        mockSingleSlice(first, second);
        when(tmdbApiClient.getMovieDetail("1"))
                .thenThrow(new TmdbApiException("boom", new RuntimeException()));
        when(tmdbApiClient.getMovieDetail("2"))
                .thenReturn(new TmdbMovieDetail(2L, "제목2", "줄거리2"));

        BackfillResult result = backfillService.backfill();

        verify(tmdbContentLocalizer, never()).localize(eq(first.getId()), any(), any());
        verify(tmdbContentLocalizer).localize(second.getId(), "제목2", "줄거리2");
        assertThat(result).isEqualTo(new BackfillResult(2, 1, 1));
    }

    @Test
    @DisplayName("상세 응답이 null이면 해당 건은 failed로 센다")
    void backfill_detailResponseIsNull_countsAsFailed() {
        Content movie = content(ContentType.MOVIE, "1");
        mockSingleSlice(movie);
        when(tmdbApiClient.getMovieDetail("1")).thenReturn(null);

        BackfillResult result = backfillService.backfill();

        verify(tmdbContentLocalizer, never()).localize(any(), any(), any());
        assertThat(result).isEqualTo(new BackfillResult(1, 0, 1));
    }

    @Test
    @DisplayName("여러 슬라이스에 걸친 콘텐츠를 모두 처리하고 total은 두 페이지 합계다")
    void backfill_paginatesAcrossMultipleSlices() {
        Content page1 = content(ContentType.MOVIE, "1");
        Content page2 = content(ContentType.MOVIE, "2");
        Slice<Content> firstSlice = new SliceImpl<>(List.of(page1), PageRequest.of(0, 100), true);
        Slice<Content> secondSlice = new SliceImpl<>(List.of(page2), PageRequest.of(1, 100), false);
        when(contentRepository.findBySource(eq(ContentSource.TMDB), any(Pageable.class)))
                .thenReturn(firstSlice, secondSlice);
        when(tmdbApiClient.getMovieDetail(any()))
                .thenReturn(new TmdbMovieDetail(1L, "제목", "줄거리"));

        BackfillResult result = backfillService.backfill();

        verify(contentRepository, times(2)).findBySource(eq(ContentSource.TMDB), any(Pageable.class));
        verify(tmdbApiClient).getMovieDetail("1");
        verify(tmdbApiClient).getMovieDetail("2");
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.updated()).isEqualTo(2);
        assertThat(result.failed()).isZero();
    }

    @Test
    @DisplayName("성공·실패·건너뜀이 섞인 시나리오에서 최종 카운트가 정확하다")
    void backfill_returnsAccurateCounts() {
        Content movieOk = content(ContentType.MOVIE, "1");
        Content movieNull = content(ContentType.MOVIE, "2");
        Content sport = content(ContentType.SPORT, "3");
        Content tvOk = content(ContentType.TV_SERIES, "4");
        mockSingleSlice(movieOk, movieNull, sport, tvOk);
        when(tmdbApiClient.getMovieDetail("1")).thenReturn(new TmdbMovieDetail(1L, "m1", "o1"));
        when(tmdbApiClient.getMovieDetail("2")).thenReturn(null);
        when(tmdbApiClient.getTvDetail("4")).thenReturn(new TmdbTvDetail(4L, "t4", "o4"));

        BackfillResult result = backfillService.backfill();

        assertThat(result).isEqualTo(new BackfillResult(4, 2, 1));
        verify(tmdbContentLocalizer).localize(movieOk.getId(), "m1", "o1");
        verify(tmdbContentLocalizer).localize(tvOk.getId(), "t4", "o4");
        verify(tmdbContentLocalizer, never()).localize(eq(movieNull.getId()), any(), any());
    }
}
