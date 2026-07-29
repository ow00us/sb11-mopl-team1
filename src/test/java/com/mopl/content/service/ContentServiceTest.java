package com.mopl.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.content.dto.ContentCreateRequest;
import com.mopl.content.dto.ContentDto;
import com.mopl.content.dto.ContentUpdateRequest;
import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentType;
import com.mopl.content.repository.ContentRepository;
import com.mopl.content.storage.ThumbnailStorage;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
}