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
import com.mopl.content.storage.ThumbnailStorage;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.util.CursorUtils;
import java.time.Instant;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
class ContentServiceTest {

    @Mock
    ContentRepository contentRepository;

    @Mock
    ThumbnailStorage thumbnailStorage;

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
    @DisplayName("존재하는 콘텐츠 단건 조회 시 ContentDto를 반환한다")
    void get_success() {
        Content content = savedContent(CONTENT_ID, "제목", "설명", null);
        when(contentRepository.findById(CONTENT_ID)).thenReturn(Optional.of(content));

        ContentDto result = contentService.get(CONTENT_ID);

        assertThat(result.id()).isEqualTo(CONTENT_ID);
        assertThat(result.title()).isEqualTo("제목");
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
    @DisplayName("tagsIn이 정규화되어 레포지토리에 전달된다")
    void getList_normalizesTags() {
        when(contentRepository.findByCreatedAtDesc(any(), any(), any(), anyInt(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(contentRepository.countByFilter(any(), any(), any(), anyInt())).thenReturn(0L);

        contentService.getList(null, null, List.of("Action", " SF "), null, null, 10, "createdAt", "DESCENDING");

        verify(contentRepository).findByCreatedAtDesc(
                isNull(), isNull(), eq(List.of("action", "sf")), eq(2), isNull(), isNull(), eq(11));
        verify(contentRepository).countByFilter(isNull(), isNull(), eq(List.of("action", "sf")), eq(2));
    }

    @Test
    @DisplayName("대소문자만 다른 중복 태그는 정규화 후 하나로 합쳐져 tagCount에 반영된다")
    void getList_deduplicatesNormalizedTags() {
        ArgumentCaptor<Integer> tagCountCaptor = ArgumentCaptor.forClass(Integer.class);
        when(contentRepository.findByCreatedAtDesc(
                any(), any(), any(), tagCountCaptor.capture(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(contentRepository.countByFilter(any(), any(), any(), anyInt())).thenReturn(0L);

        contentService.getList(
                null, null, List.of("Action", "ACTION", "SF"), null, null, 10, "createdAt", "DESCENDING");

        assertThat(tagCountCaptor.getValue()).isEqualTo(2);
    }

    @Test
    @DisplayName("tagsIn이 없으면 더미 태그와 tagCount 0이 레포지토리에 전달된다")
    void getList_withoutTags_passesDummyTagAndZeroCount() {
        when(contentRepository.findByCreatedAtDesc(any(), any(), any(), anyInt(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(contentRepository.countByFilter(any(), any(), any(), anyInt())).thenReturn(0L);

        contentService.getList(null, null, null, null, null, 10, "createdAt", "DESCENDING");

        verify(contentRepository).findByCreatedAtDesc(
                isNull(), isNull(), eq(List.of("")), eq(0), isNull(), isNull(), eq(11));
    }

    @Test
    @DisplayName("typeEqual은 DB 저장 형식(enum name)으로 변환되어 전달된다")
    void getList_convertsTypeEqualToEnumName() {
        when(contentRepository.findByCreatedAtDesc(any(), any(), any(), anyInt(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(contentRepository.countByFilter(any(), any(), any(), anyInt())).thenReturn(0L);

        contentService.getList("tvSeries", null, null, null, null, 10, "createdAt", "DESCENDING");

        verify(contentRepository).findByCreatedAtDesc(
                eq("TV_SERIES"), isNull(), any(), anyInt(), isNull(), isNull(), eq(11));
    }

    @Test
    @DisplayName("keywordLike에 LIKE 와일드카드 문자가 포함되면 이스케이프해서 리포지토리에 전달한다")
    void getList_escapesLikeWildcardsInKeyword() {
        ArgumentCaptor<String> keywordCaptor = ArgumentCaptor.forClass(String.class);
        when(contentRepository.findByCreatedAtDesc(
                any(), keywordCaptor.capture(), any(), anyInt(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(contentRepository.countByFilter(any(), any(), any(), anyInt())).thenReturn(0L);

        contentService.getList(null, "50%_off", null, null, null, 10, "createdAt", "DESCENDING");

        assertThat(keywordCaptor.getValue()).isEqualTo("50\\%\\_off");
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

        verifyNoInteractions(contentRepository);
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
        when(contentRepository.findByWatcherCountDesc(any(), any(), any(), anyInt(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(contentRepository.countByFilter(any(), any(), any(), anyInt())).thenReturn(0L);

        contentService.getList(null, null, null, null, null, 10, "watcherCount", "DESCENDING");

        verify(contentRepository).findByWatcherCountDesc(
                isNull(), isNull(), any(), anyInt(), isNull(), isNull(), isNull(), eq(11));
    }

    @Test
    @DisplayName("watcherCount DESC 정렬 시 다음 페이지가 있으면 (watcherCount, reviewCount) 복합 커서를 반환한다")
    void getList_watcherCountDescSort_returnsCompositeCursor() {
        Content lastOfPage = savedContentWithId(UUID.randomUUID(), "B");
        ReflectionTestUtils.setField(lastOfPage, "watcherCount", 10L);
        ReflectionTestUtils.setField(lastOfPage, "reviewCount", 3L);
        List<Content> rows = List.of(
                savedContentWithId(UUID.randomUUID(), "A"),
                lastOfPage,
                savedContentWithId(UUID.randomUUID(), "C"));
        when(contentRepository.findByWatcherCountDesc(any(), any(), any(), anyInt(), any(), any(), any(), eq(3)))
                .thenReturn(rows);
        when(contentRepository.countByFilter(any(), any(), any(), anyInt())).thenReturn(5L);

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
        when(contentRepository.findByAverageRatingAsc(any(), any(), any(), anyInt(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(contentRepository.countByFilter(any(), any(), any(), anyInt())).thenReturn(0L);

        contentService.getList(null, null, null, null, null, 10, "averageRating", "ASCENDING");

        verify(contentRepository).findByAverageRatingAsc(
                isNull(), isNull(), any(), anyInt(), isNull(), isNull(), eq(11));
    }

    @Test
    @DisplayName("createdAt 정렬 시 다음 페이지가 있으면 hasNext true와 nextCursor를 반환한다")
    void getList_createdAtSort_hasNextPage() {
        List<Content> rows = List.of(
                savedContentWithId(UUID.randomUUID(), "A"),
                savedContentWithId(UUID.randomUUID(), "B"),
                savedContentWithId(UUID.randomUUID(), "C"));
        when(contentRepository.findByCreatedAtDesc(any(), any(), any(), anyInt(), any(), any(), eq(3)))
                .thenReturn(rows);
        when(contentRepository.countByFilter(any(), any(), any(), anyInt())).thenReturn(5L);

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
        List<Content> rows = List.of(savedContentWithId(UUID.randomUUID(), "A"));
        when(contentRepository.findByCreatedAtAsc(any(), any(), any(), anyInt(), any(), any(), eq(3)))
                .thenReturn(rows);
        when(contentRepository.countByFilter(any(), any(), any(), anyInt())).thenReturn(1L);

        CursorResponse<ContentDto> result = contentService.getList(
                null, null, null, null, null, 2, "createdAt", "ASCENDING");

        assertThat(result.data()).hasSize(1);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    @DisplayName("watcherCount 정렬 시 다음 페이지가 있으면 hasNext true와 nextCursor를 반환한다")
    void getList_watcherCountSort_hasNextPage() {
        List<Content> rows = List.of(
                savedContentWithId(UUID.randomUUID(), "A"),
                savedContentWithId(UUID.randomUUID(), "B"),
                savedContentWithId(UUID.randomUUID(), "C"));
        when(contentRepository.findByWatcherCountAsc(any(), any(), any(), anyInt(), any(), any(), eq(3)))
                .thenReturn(rows);
        when(contentRepository.countByFilter(any(), any(), any(), anyInt())).thenReturn(5L);

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
        List<Content> rows = List.of(
                savedContentWithId(UUID.randomUUID(), "A"),
                savedContentWithId(UUID.randomUUID(), "B"),
                savedContentWithId(UUID.randomUUID(), "C"));
        when(contentRepository.findByAverageRatingDesc(any(), any(), any(), anyInt(), any(), any(), eq(3)))
                .thenReturn(rows);
        when(contentRepository.countByFilter(any(), any(), any(), anyInt())).thenReturn(5L);

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

    private Content savedContentWithId(UUID id, String title) {
        Content content = Content.builder()
                .type(ContentType.MOVIE)
                .title(title)
                .description("설명")
                .build();
        ReflectionTestUtils.setField(content, "id", id);
        ReflectionTestUtils.setField(content, "createdAt", Instant.now());
        return content;
    }
}