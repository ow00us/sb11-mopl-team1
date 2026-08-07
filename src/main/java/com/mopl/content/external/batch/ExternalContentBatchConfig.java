package com.mopl.content.external.batch;

import com.mopl.content.external.mapping.ContentUpsertService;
import com.mopl.content.external.mapping.ExternalContentDraft;
import com.mopl.content.external.mapping.SportsDbContentMapper;
import com.mopl.content.external.mapping.TmdbContentMapper;
import com.mopl.content.external.sportsdb.SportsDbApiClient;
import com.mopl.content.external.sportsdb.SportsDbProperties;
import com.mopl.content.external.sportsdb.dto.SportsDbEventSummary;
import com.mopl.content.external.tmdb.TmdbApiClient;
import com.mopl.content.external.tmdb.dto.TmdbMovieSummary;
import com.mopl.content.external.tmdb.dto.TmdbTvSummary;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@RequiredArgsConstructor
public class ExternalContentBatchConfig {

    private static final int CHUNK_SIZE = 20;
    private static final int SKIP_LIMIT = 20;

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final TmdbApiClient tmdbApiClient;
    private final SportsDbApiClient sportsDbApiClient;
    private final TmdbContentMapper tmdbContentMapper;
    private final SportsDbContentMapper sportsDbContentMapper;
    private final ContentUpsertService contentUpsertService;
    private final ExternalContentBatchProperties batchProperties;
    private final SportsDbProperties sportsDbProperties;

    @Bean
    public Job externalContentCollectionJob() {
        return new JobBuilder("externalContentCollectionJob", jobRepository)
                .start(tmdbMovieCollectionStep())
                .next(tmdbTvCollectionStep())
                .next(sportsDbEventCollectionStep())
                .build();
    }

    @Bean
    public Step tmdbMovieCollectionStep() {
        return new StepBuilder("tmdbMovieCollectionStep", jobRepository)
                .<TmdbMovieSummary, ExternalContentDraft>chunk(CHUNK_SIZE, transactionManager)
                .reader(tmdbMovieItemReader())
                .processor(tmdbContentMapper::toDraft)
                .writer(contentDraftItemWriter())
                .faultTolerant()
                .skipLimit(SKIP_LIMIT)
                .skip(RuntimeException.class)
                .build();
    }

    @Bean
    @StepScope
    public TmdbMoviePopularItemReader tmdbMovieItemReader() {
        return new TmdbMoviePopularItemReader(tmdbApiClient, batchProperties.tmdbMaxPages());
    }

    @Bean
    public Step tmdbTvCollectionStep() {
        return new StepBuilder("tmdbTvCollectionStep", jobRepository)
                .<TmdbTvSummary, ExternalContentDraft>chunk(CHUNK_SIZE, transactionManager)
                .reader(tmdbTvItemReader())
                .processor(tmdbContentMapper::toDraft)
                .writer(contentDraftItemWriter())
                .faultTolerant()
                .skipLimit(SKIP_LIMIT)
                .skip(RuntimeException.class)
                .build();
    }

    @Bean
    @StepScope
    public TmdbTvPopularItemReader tmdbTvItemReader() {
        return new TmdbTvPopularItemReader(tmdbApiClient, batchProperties.tmdbMaxPages());
    }

    @Bean
    public Step sportsDbEventCollectionStep() {
        return new StepBuilder("sportsDbEventCollectionStep", jobRepository)
                .<SportsDbEventSummary, ExternalContentDraft>chunk(CHUNK_SIZE, transactionManager)
                .reader(sportsDbEventItemReader())
                .processor(item -> sportsDbContentMapper.toDraft(item).orElse(null))
                .writer(contentDraftItemWriter())
                .faultTolerant()
                .skipLimit(SKIP_LIMIT)
                .skip(RuntimeException.class)
                .build();
    }

    @Bean
    @StepScope
    public SportsDbEventItemReader sportsDbEventItemReader() {
        ZoneId zone = ZoneId.of(batchProperties.zone());
        List<LocalDate> dates = buildDateRange(batchProperties.sportsDbPastDays(), batchProperties.sportsDbFutureDays(), zone);
        return new SportsDbEventItemReader(sportsDbApiClient, sportsDbProperties.leagueIds(), dates);
    }

    private List<LocalDate> buildDateRange(int pastDays, int futureDays, ZoneId zone) {
        LocalDate today = LocalDate.now(zone);
        List<LocalDate> dates = new ArrayList<>();
        for (int offset = -pastDays; offset <= futureDays; offset++) {
            dates.add(today.plusDays(offset));
        }
        return dates;
    }

    @Bean
    public ItemWriter<ExternalContentDraft> contentDraftItemWriter() {
        return chunk -> {
            for (ExternalContentDraft draft : chunk) {
                contentUpsertService.upsert(draft);
            }
        };
    }
}