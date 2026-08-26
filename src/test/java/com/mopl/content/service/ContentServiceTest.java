package com.mopl.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.content.dto.ContentCreateRequest;
import com.mopl.content.dto.ContentDto;
import com.mopl.content.dto.ContentUpdateRequest;
import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentType;
import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.ContentDocument;
import com.mopl.content.search.ContentSearchDeleteEvent;
import com.mopl.content.search.ContentSearchExecutor;
import com.mopl.content.search.ContentSearchSyncEvent;
import com.mopl.content.storage.ThumbnailStorage;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.util.CursorUtils;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
class ContentServiceTest {

    @Mock
    ContentRepository contentRepository;

    @Mock
    ContentSearchExecutor contentSearchExecutor;

    @Mock
    ThumbnailStorage thumbnailStorage;

    @Mock
    WatchingSessionSnapshotRepository watchingSessionSnapshotRepository;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @InjectMocks
    ContentServiceImpl contentService;

    private static final UUID CONTENT_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final String THUMBNAIL_URL = "https://placeholder.mopl.local/thumbnails/x-thumb.png";

    // ── create ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("콘텐츠 생성 시 썸네일을 업로드하고 태그를 추가해 저장한다")
    void create_success() {
        ContentCreateRequest request = new ContentCreateRequest(
                ContentType.MOVIE, "제목", "설명", List.of("Action", " SF "));
        MultipartFile thumbnail = new MockMultipartFile("thumbnail", "t.png", "image/png", new byte[]{1});
        when(thumbnailStorage.upload(thumbnail)).thenReturn(THUMBNAIL_URL);
        Content saved = savedContent(CONTENT_ID, "제목", "설명", THUMBNAIL_URL);
        when(contentRepository.save(any(Content.class))).thenReturn(saved);

        ContentDto result = contentService.create(request, thumbnail);

        assertThat(result.title()).isEqualTo("제목");
        assertThat(result.thumbnailUrl()).isEqualTo(THUMBNAIL_URL);
        verify(thumbnailStorage).upload(thumbnail);
        verify(contentRepository).save(any(Content.class));
        verify(eventPublisher).publishEvent(new ContentSearchSyncEvent(saved.getId()));
    }

    @Test
    @DisplayName("썸네일 없이 생성하면 업로드를 호출하지 않고 콘텐츠를 저장한다")
    void create_success_withoutThumbnail() {
        ContentCreateRequest request = new ContentCreateRequest(
                ContentType.MOVIE, "제목", "설명", List.of("action"));
        Content saved = savedContent(CONTENT_ID, "제목", "설명", null);
        when(contentRepository.save(any(Content.class))).thenReturn(saved);

        ContentDto result = contentService.create(request, null);

        assertThat(result.thumbnailUrl()).isNull();
        verify(thumbnailStorage, never()).upload(any());
    }

    @Test
    @DisplayName("태그가 유효하지 않으면 썸네일 업로드를 호출하지 않는다")
    void create_fail_invalidTag_doesNotUploadThumbnail() {
        ContentCreateRequest request = new ContentCreateRequest(
                ContentType.MOVIE, "제목", "설명", List.of("   "));
        MultipartFile thumbnail = new MockMultipartFile("thumbnail", "t.png", "image/png", new byte[]{1});

        assertThatThrownBy(() -> contentService.create(request, thumbnail))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verify(thumbnailStorage, never()).upload(any());
        verify(contentRepository, never()).save(any());
    }

    // ── get ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("존재하는 콘텐츠 단건 조회 시 실시간 watcherCount를 반영한 ContentDto를 반환한다")
    void get_success() {
        Content content = savedContent(CONTENT_ID, "제목", "설명", null);
        when(contentRepository.findById(CONTENT_ID)).thenReturn(Optional.of(content));
        when(watchingSessionSnapshotRepository.countByContentId(any(), any(), any())).thenReturn(4L);

        ContentDto result = contentService.get(CONTENT_ID);

        assertThat(result.id()).isEqualTo(CONTENT_ID);
        assertThat(result.title()).isEqualTo("제목");
        assertThat(result.watcherCount()).isEqualTo(4L);
    }

    @Test
    @DisplayName("존재하지 않는 ID 조회 시 RESOURCE_NOT_FOUND 예외가 발생한다")
    void get_fail_notFound() {
        when(contentRepository.findById(CONTENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> contentService.get(CONTENT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ── getList ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("tagsIn이 정규화되어 검색 실행기에 전달된다")
    void getList_normalizesTags() {
        when(contentSearchExecutor.findByCreatedAtDesc(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(contentSearchExecutor.countByFilter(any(), any(), any())).thenReturn(0L);

        contentService.getList(null, null, List.of("Action", " SF "), null, null, 10, "createdAt", "DESCENDING");

        verify(contentSearchExecutor).findByCreatedAtDesc(
                any(), any(), eq(List.of("action", "sf")), any(), any(), anyInt());
    }

    @Test
    @DisplayName("대소문자만 다른 중복 태그는 정규화 후 하나로 합쳐져 전달된다")
    void getList_deduplicatesNormalizedTags() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> tagsCaptor = ArgumentCaptor.forClass(List.class);
        when(contentSearchExecutor.findByCreatedAtDesc(
                any(), any(), tagsCaptor.capture(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(contentSearchExecutor.countByFilter(any(), any(), any())).thenReturn(0L);

        contentService.getList(
                null, null, List.of("Action", "ACTION", "SF"), null, null, 10, "createdAt", "DESCENDING");

        assertThat(tagsCaptor.getValue()).containsExactlyInAnyOrder("action", "sf");
    }

    @Test
    @DisplayName("tagsIn이 없으면 빈 리스트가 전달된다")
    void getList_withoutTags_passesEmptyList() {
        when(contentSearchExecutor.findByCreatedAtDesc(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(contentSearchExecutor.countByFilter(any(), any(), any())).thenReturn(0L);

        contentService.getList(null, null, null, null, null, 10, "createdAt", "DESCENDING");

        verify(contentSearchExecutor).findByCreatedAtDesc(
                isNull(), isNull(), eq(List.of()), isNull(), isNull(), eq(11));
    }

    @Test
    @DisplayName("typeEqual은 변환 없이 원본 API 값 그대로 전달된다")
    void getList_passesTypeEqualAsRawApiValue() {
        when(contentSearchExecutor.findByCreatedAtDesc(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(contentSearchExecutor.countByFilter(any(), any(), any())).thenReturn(0L);

        contentService.getList("tvSeries", null, null, null, null, 10, "createdAt", "DESCENDING");

        verify(contentSearchExecutor).findByCreatedAtDesc(
                eq("tvSeries"), isNull(), any(), isNull(), isNull(), eq(11));
    }

    @Test
    @DisplayName("keywordLike는 이스케이프 없이 그대로 전달된다")
    void getList_passesKeywordLikeAsIs() {
        ArgumentCaptor<String> keywordCaptor = ArgumentCaptor.forClass(String.class);
        when(contentSearchExecutor.findByCreatedAtDesc(
                any(), keywordCaptor.capture(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(contentSearchExecutor.countByFilter(any(), any(), any())).thenReturn(0L);

        contentService.getList(null, "50%_off", null, null, null, 10, "createdAt", "DESCENDING");

        assertThat(keywordCaptor.getValue()).isEqualTo("50%_off");
    }

    @Test
    @DisplayName("limit이 1 미만이면 INVALID_INPUT 예외가 발생한다")
    void getList_fail_limitTooSmall() {
        assertThatThrownBy(() -> contentService.getList(
                null, null, null, null, null, 0, "createdAt", "DESCENDING"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("limit이 100을 초과하면 INVALID_INPUT 예외가 발생한다")
    void getList_fail_limitTooLarge() {
        assertThatThrownBy(() -> contentService.getList(
                null, null, null, null, null, 101, "createdAt", "DESCENDING"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("limit이 Integer.MAX_VALUE에 가까워도 오버플로우 없이 INVALID_INPUT 예외가 발생한다")
    void getList_fail_limitNearIntegerMax() {
        assertThatThrownBy(() -> contentService.getList(
                null, null, null, null, null, Integer.MAX_VALUE, "createdAt", "DESCENDING"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("인식되지 않는 sortBy 값이면 리포지토리 호출 없이 INVALID_INPUT 예외가 발생한다")
    void getList_fail_invalidSortBy() {
        assertThatThrownBy(() -> contentService.getList(
                null, null, null, null, null, 10, "watcher_count", "DESCENDING"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verifyNoInteractions(contentSearchExecutor);
    }

    @Test
    @DisplayName("cursor만 있고 idAfter가 없으면 INVALID_INPUT 예외가 발생한다")
    void getList_fail_cursorWithoutIdAfter() {
        String validCursor = CursorUtils.encodeInstant(Instant.now());

        assertThatThrownBy(() -> contentService.getList(
                null, null, null, validCursor, null, 10, "createdAt", "DESCENDING"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("Base64는 유효하지만 날짜 형식이 아닌 createdAt 커서는 INVALID_INPUT 예외가 발생한다")
    void getList_fail_invalidInstantCursor() {
        String invalidInstantCursor = CursorUtils.encode("not-a-date");

        assertThatThrownBy(() -> contentService.getList(
                null, null, null, invalidInstantCursor, UUID.randomUUID(), 10, "createdAt", "DESCENDING"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("잘못된 cursor 값이면 INVALID_INPUT 예외가 발생한다")
    void getList_fail_invalidCursor() {
        assertThatThrownBy(() -> contentService.getList(
                null, null, null, "not-a-valid-cursor!!", UUID.randomUUID(), 10, "createdAt", "DESCENDING"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("watcherCount DESC 정렬이면 findByWatcherCountDesc를 호출한다")
    void getList_watcherCountSort_descending_callsFindByWatcherCountDesc() {
        when(contentSearchExecutor.findByWatcherCountDesc(
                any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(contentSearchExecutor.countByFilter(any(), any(), any())).thenReturn(0L);

        contentService.getList(null, null, null, null, null, 10, "watcherCount", "DESCENDING");

        verify(contentSearchExecutor).findByWatcherCountDesc(
                isNull(), isNull(), any(), isNull(), isNull(), isNull(), eq(11));
    }

    @Test
    @DisplayName("watcherCount DESC 정렬 시 다음 페이지가 있으면 마지막 문서의 (watcherCount, reviewCount)로 복합 커서를 반환한다")
    void getList_watcherCountDescSort_returnsCompositeCursor() {
        ContentDocument lastOfPage = searchDocument(UUID.randomUUID(), "B", 10, 3);
        List<ContentDocument> rows = List.of(
                searchDocument(UUID.randomUUID(), "A", 0, 0),
                lastOfPage,
                searchDocument(UUID.randomUUID(), "C", 0, 0));
        when(contentSearchExecutor.findByWatcherCountDesc(
                any(), any(), any(), any(), any(), any(), eq(3)))
                .thenReturn(rows);
        when(contentSearchExecutor.countByFilter(any(), any(), any())).thenReturn(5L);

        CursorResponse<ContentDto> result = contentService.getList(
                null, null, null, null, null, 2, "watcherCount", "DESCENDING");

        CursorUtils.LongPair decoded = CursorUtils.decodeAsLongPair(result.nextCursor());
        assertThat(decoded.first()).isEqualTo(10L);
        assertThat(decoded.second()).isEqualTo(3L);
    }

    @Test
    @DisplayName("형식이 잘못된 watcherCount 복합 커서는 INVALID_INPUT 예외가 발생한다")
    void getList_fail_invalidWatcherCountPairCursor() {
        String invalidPairCursor = CursorUtils.encode("not-a-pair");

        assertThatThrownBy(() -> contentService.getList(
                null, null, null, invalidPairCursor, UUID.randomUUID(), 10, "watcherCount", "DESCENDING"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("averageRating ASC 정렬이면 findByAverageRatingAsc를 호출한다")
    void getList_averageRatingSort_ascending_callsFindByAverageRatingAsc() {
        when(contentSearchExecutor.findByAverageRatingAsc(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(contentSearchExecutor.countByFilter(any(), any(), any())).thenReturn(0L);

        contentService.getList(null, null, null, null, null, 10, "averageRating", "ASCENDING");

        verify(contentSearchExecutor).findByAverageRatingAsc(
                isNull(), isNull(), any(), isNull(), isNull(), eq(11));
    }

    @Test
    @DisplayName("createdAt 정렬 시 다음 페이지가 있으면 hasNext true와 nextCursor를 반환한다")
    void getList_createdAtSort_hasNextPage() {
        List<ContentDocument> rows = List.of(
                searchDocument(UUID.randomUUID(), "A", 0, 0),
                searchDocument(UUID.randomUUID(), "B", 0, 0),
                searchDocument(UUID.randomUUID(), "C", 0, 0));
        when(contentSearchExecutor.findByCreatedAtDesc(any(), any(), any(), any(), any(), eq(3)))
                .thenReturn(rows);
        when(contentSearchExecutor.countByFilter(any(), any(), any())).thenReturn(5L);

        CursorResponse<ContentDto> result = contentService.getList(
                null, null, null, null, null, 2, "createdAt", "DESCENDING");

        assertThat(result.data()).hasSize(2);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.nextCursor()).isNotNull();
        assertThat(result.nextIdAfter()).isNotNull();
    }

    @Test
    @DisplayName("idAfter만 있고 cursor가 없으면 INVALID_INPUT 예외가 발생한다")
    void getList_fail_idAfterWithoutCursor() {
        assertThatThrownBy(() -> contentService.getList(
                null, null, null, null, UUID.randomUUID(), 10, "createdAt", "DESCENDING"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("createdAt 정렬 첫 페이지 조회 시 hasNext false를 반환한다")
    void getList_createdAtSort_firstPage_noNextPage() {
        List<ContentDocument> rows = List.of(searchDocument(UUID.randomUUID(), "A", 0, 0));
        when(contentSearchExecutor.findByCreatedAtAsc(any(), any(), any(), any(), any(), eq(3)))
                .thenReturn(rows);
        when(contentSearchExecutor.countByFilter(any(), any(), any())).thenReturn(1L);

        CursorResponse<ContentDto> result = contentService.getList(
                null, null, null, null, null, 2, "createdAt", "ASCENDING");

        assertThat(result.data()).hasSize(1);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    @DisplayName("watcherCount 정렬 시 다음 페이지가 있으면 hasNext true와 nextCursor를 반환한다")
    void getList_watcherCountSort_hasNextPage() {
        List<ContentDocument> rows = List.of(
                searchDocument(UUID.randomUUID(), "A", 0, 0),
                searchDocument(UUID.randomUUID(), "B", 0, 0),
                searchDocument(UUID.randomUUID(), "C", 0, 0));
        when(contentSearchExecutor.findByWatcherCountAsc(any(), any(), any(), any(), any(), eq(3)))
                .thenReturn(rows);
        when(contentSearchExecutor.countByFilter(any(), any(), any())).thenReturn(5L);

        CursorResponse<ContentDto> result = contentService.getList(
                null, null, null, null, null, 2, "watcherCount", "ASCENDING");

        assertThat(result.data()).hasSize(2);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.nextCursor()).isNotNull();
        assertThat(result.nextIdAfter()).isNotNull();
    }

    @Test
    @DisplayName("averageRating 정렬 시 다음 페이지가 있으면 hasNext true와 nextCursor를 반환한다")
    void getList_averageRatingSort_hasNextPage() {
        List<ContentDocument> rows = List.of(
                searchDocument(UUID.randomUUID(), "A", 0, 0),
                searchDocument(UUID.randomUUID(), "B", 0, 0),
                searchDocument(UUID.randomUUID(), "C", 0, 0));
        when(contentSearchExecutor.findByAverageRatingDesc(any(), any(), any(), any(), any(), eq(3)))
                .thenReturn(rows);
        when(contentSearchExecutor.countByFilter(any(), any(), any())).thenReturn(5L);

        CursorResponse<ContentDto> result = contentService.getList(
                null, null, null, null, null, 2, "averageRating", "DESCENDING");

        assertThat(result.data()).hasSize(2);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.nextCursor()).isNotNull();
    }

    // ── update ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("thumbnail 없이 수정하면 title/description/tags만 변경되고 업로드는 호출되지 않는다")
    void update_withoutThumbnail_doesNotUpload() {
        Content content = savedContent(CONTENT_ID, "원래 제목", "원래 설명", null);
        when(contentRepository.findById(CONTENT_ID)).thenReturn(Optional.of(content));
        when(contentRepository.save(any(Content.class))).thenReturn(content);

        ContentDto result = contentService.update(
                CONTENT_ID, new ContentUpdateRequest("새 제목", null, null), null);

        assertThat(result.title()).isEqualTo("새 제목");
        verify(thumbnailStorage, never()).upload(any());
        verify(eventPublisher).publishEvent(new ContentSearchSyncEvent(CONTENT_ID));
    }

    @Test
    @DisplayName("tags가 주어지면 콘텐츠의 태그가 전체 교체된다")
    void update_withTags_replacesTags() {
        Content content = savedContent(CONTENT_ID, "제목", "설명", null);
        when(contentRepository.findById(CONTENT_ID)).thenReturn(Optional.of(content));
        when(contentRepository.save(any(Content.class))).thenReturn(content);

        contentService.update(
                CONTENT_ID, new ContentUpdateRequest(null, null, List.of("Action", " SF ")), null);

        assertThat(content.getTags()).containsExactlyInAnyOrder("action", "sf");
    }

    @Test
    @DisplayName("빈 MultipartFile로 수정하면 업로드가 호출되지 않는다")
    void update_withEmptyThumbnail_doesNotUpload() {
        Content content = savedContent(CONTENT_ID, "제목", "설명", null);
        when(contentRepository.findById(CONTENT_ID)).thenReturn(Optional.of(content));
        when(contentRepository.save(any(Content.class))).thenReturn(content);
        MultipartFile emptyThumbnail = new MockMultipartFile("thumbnail", new byte[0]);

        contentService.update(CONTENT_ID, new ContentUpdateRequest(null, null, null), emptyThumbnail);

        verify(thumbnailStorage, never()).upload(any());
    }

    @Test
    @DisplayName("유효한 thumbnail이 주어지면 업로드 후 썸네일이 갱신된다")
    void update_withThumbnail_uploadsAndUpdatesThumbnail() {
        Content content = savedContent(CONTENT_ID, "제목", "설명", "https://old.example.com/thumb.png");
        when(contentRepository.findById(CONTENT_ID)).thenReturn(Optional.of(content));
        when(contentRepository.save(any(Content.class))).thenReturn(content);
        MultipartFile thumbnail = new MockMultipartFile("thumbnail", "new.png", "image/png", new byte[]{1});
        when(thumbnailStorage.upload(thumbnail)).thenReturn(THUMBNAIL_URL);

        ContentDto result = contentService.update(
                CONTENT_ID, new ContentUpdateRequest(null, null, null), thumbnail);

        assertThat(result.thumbnailUrl()).isEqualTo(THUMBNAIL_URL);
        verify(thumbnailStorage).upload(thumbnail);
    }

    @Test
    @DisplayName("존재하지 않는 콘텐츠 수정 시 RESOURCE_NOT_FOUND 예외가 발생한다")
    void update_fail_notFound() {
        when(contentRepository.findById(CONTENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> contentService.update(
                CONTENT_ID, new ContentUpdateRequest("제목", null, null), null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ── delete ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("존재하는 콘텐츠를 삭제하면 repository.delete가 호출된다")
    void delete_success() {
        Content content = savedContent(CONTENT_ID, "제목", "설명", null);
        when(contentRepository.findById(CONTENT_ID)).thenReturn(Optional.of(content));

        contentService.delete(CONTENT_ID);

        verify(contentRepository).delete(content);
        verify(eventPublisher).publishEvent(new ContentSearchDeleteEvent(CONTENT_ID));
    }

    @Test
    @DisplayName("존재하지 않는 콘텐츠 삭제 시 RESOURCE_NOT_FOUND 예외가 발생한다")
    void delete_fail_notFound() {
        when(contentRepository.findById(CONTENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> contentService.delete(CONTENT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        verify(contentRepository, never()).delete(any());
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private Content savedContent(UUID id, String title, String description, String thumbnailUrl) {
        Content content = Content.builder()
                .type(ContentType.MOVIE)
                .title(title)
                .description(description)
                .thumbnailUrl(thumbnailUrl)
                .build();
        ReflectionTestUtils.setField(content, "id", id);
        return content;
    }

    private ContentDocument searchDocument(UUID id, String title, long watcherCount, long reviewCount) {
        Instant now = Instant.now();
        return ContentDocument.builder()
                .id(id.toString())
                .contentId(id.toString())
                .title(title)
                .description("설명")
                .type(ContentType.MOVIE.name())
                .tags(List.of())
                .averageRating(0.0)
                .watcherCount((int) watcherCount)
                .reviewCount((int) reviewCount)
                .createdAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .createdAtEpochMicros(now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000)
                .build();
    }
}