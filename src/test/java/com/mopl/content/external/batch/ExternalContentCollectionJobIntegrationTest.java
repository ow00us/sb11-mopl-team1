package com.mopl.content.external.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentSource;
import com.mopl.content.entity.ContentType;
import com.mopl.content.external.sportsdb.SportsDbApiClient;
import com.mopl.content.external.sportsdb.dto.SportsDbEventSummary;
import com.mopl.content.external.tmdb.TmdbApiClient;
import com.mopl.content.external.tmdb.dto.TmdbMovieSummary;
import com.mopl.content.external.tmdb.dto.TmdbPopularMoviesResponse;
import com.mopl.content.external.tmdb.dto.TmdbPopularTvResponse;
import com.mopl.content.external.tmdb.dto.TmdbTvSummary;
import com.mopl.content.repository.ContentRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@SpringBatchTest
@ActiveProfiles("test")
@Testcontainers
class ExternalContentCollectionJobIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    ContentRepository contentRepository;

    @MockitoBean
    TmdbApiClient tmdbApiClient;

    @MockitoBean
    SportsDbApiClient sportsDbApiClient;

    @Test
    @DisplayName("Job을 실행하면 TMDB·Sports DB 콘텐츠가 수집되어 DB에 저장된다")
    void job_collectsTmdbAndSportsDbContent_andSavesToDatabase() throws Exception {
        TmdbMovieSummary movie = new TmdbMovieSummary(9001L, "Test Movie", "movie overview", "/m.jpg", List.of(28));
        when(tmdbApiClient.getPopularMovies(1)).thenReturn(new TmdbPopularMoviesResponse(1, List.of(movie), 1));

        TmdbTvSummary tv = new TmdbTvSummary(9002L, "Test TV", "tv overview", "/t.jpg", List.of(18));
        when(tmdbApiClient.getPopularTvShows(1)).thenReturn(new TmdbPopularTvResponse(1, List.of(tv), 1));

        SportsDbEventSummary event = new SportsDbEventSummary(
                "9003", "Test Match", "Test League", "Soccer", LocalDate.now().toString(), "https://thumb.jpg", "Test Filename");
        when(sportsDbApiClient.getEventsByDay(LocalDate.now(), 4569)).thenReturn(List.of(event));

        JobParameters jobParameters = new JobParametersBuilder()
                .addLocalDateTime("runDateTime", LocalDateTime.now())
                .toJobParameters();

        var jobExecution = jobLauncherTestUtils.launchJob(jobParameters);

        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(contentRepository.findBySourceAndExternalId(ContentSource.TMDB, "9001")).isPresent();
        assertThat(contentRepository.findBySourceAndExternalId(ContentSource.TMDB, "9002")).isPresent();
        assertThat(contentRepository.findBySourceAndExternalId(ContentSource.SPORTS_DB, "9003")).isPresent();
    }

    @Test
    @DisplayName("동일한 external_id로 Job을 재실행해도 중복 저장되지 않는다")
    void job_rerunWithSameExternalIds_doesNotCreateDuplicates() throws Exception {
        TmdbMovieSummary movie = new TmdbMovieSummary(9001L, "Test Movie", "movie overview", "/m.jpg", List.of(28));
        when(tmdbApiClient.getPopularMovies(1)).thenReturn(new TmdbPopularMoviesResponse(1, List.of(movie), 1));
        when(tmdbApiClient.getPopularTvShows(1)).thenReturn(new TmdbPopularTvResponse(1, List.of(), 0));
        when(sportsDbApiClient.getEventsByDay(LocalDate.now(), 4569)).thenReturn(List.of());

        jobLauncherTestUtils.launchJob(new JobParametersBuilder()
                .addLocalDateTime("runDateTime", LocalDateTime.now())
                .toJobParameters());
        jobLauncherTestUtils.launchJob(new JobParametersBuilder()
                .addLocalDateTime("runDateTime", LocalDateTime.now().plusSeconds(1))
                .toJobParameters());

        long count = contentRepository.findAll().stream()
                .filter(c -> c.getSource() == ContentSource.TMDB && "9001".equals(c.getExternalId()))
                .count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 삭제된 콘텐츠를 Job이 재수집해도 복구되지 않고 건너뛴다")
    void job_recollectsPreviouslyDeletedContent_skipsWithoutRestoring() throws Exception {
        // 미리 저장 후 논리 삭제 (실제 어드민 삭제 흐름과 동일하게 repository.delete()로 @SQLDelete를 태운다)
        Content saved = contentRepository.saveAndFlush(Content.builder()
                .type(ContentType.MOVIE)
                .source(ContentSource.TMDB)
                .externalId("9201")
                .title("원래 제목")
                .description("원래 설명")
                .build());
        contentRepository.delete(saved);
        contentRepository.flush();

        // 같은 external_id로 TMDB가 다시 이 콘텐츠를 인기 목록에 올린 상황을 시뮬레이션
        TmdbMovieSummary recollected = new TmdbMovieSummary(9201L, "재수집된 제목", "재수집된 설명", "/m.jpg", List.of(28));
        when(tmdbApiClient.getPopularMovies(1)).thenReturn(new TmdbPopularMoviesResponse(1, List.of(recollected), 1));
        when(tmdbApiClient.getPopularTvShows(1)).thenReturn(new TmdbPopularTvResponse(1, List.of(), 0));
        when(sportsDbApiClient.getEventsByDay(LocalDate.now(), 4569)).thenReturn(List.of());

        JobExecution jobExecution = jobLauncherTestUtils.launchJob(new JobParametersBuilder()
                .addLocalDateTime("runDateTime", LocalDateTime.now())
                .toJobParameters());

        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // 삭제된 행은 그대로 삭제 상태로 남아있고, 원래 제목도 덮어써지지 않는다
        Content stillDeleted = contentRepository.findBySourceAndExternalIdIncludingDeleted(
                        ContentSource.TMDB.name(), "9201")
                .orElseThrow();
        assertThat(stillDeleted.getDeletedAt()).isNotNull();
        assertThat(stillDeleted.getTitle()).isEqualTo("원래 제목");

        // 활성 상태로는 조회되지 않는다 (복구되지 않았다)
        assertThat(contentRepository.findBySourceAndExternalId(ContentSource.TMDB, "9201")).isEmpty();

        // Step이 faultTolerant().skip(RuntimeException.class)로 설정돼 있어서, 스킵 로직이 없어도
        // unique 제약 위반이 조용히 삼켜져 위 상태 검증만으로는 회귀를 잡지 못한다.
        // skip count가 0인지 확인해야 "예외 없이 애초에 INSERT를 시도하지 않았다"는 걸 증명할 수 있다.
        StepExecution movieStep = jobExecution.getStepExecutions().stream()
                .filter(se -> se.getStepName().equals("tmdbMovieCollectionStep"))
                .findFirst()
                .orElseThrow();
        assertThat(movieStep.getSkipCount()).isZero();
    }
}