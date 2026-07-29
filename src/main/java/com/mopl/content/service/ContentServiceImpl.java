package com.mopl.content.service;

import com.mopl.content.dto.ContentCreateRequest;
import com.mopl.content.dto.ContentDto;
import com.mopl.content.dto.ContentUpdateRequest;
import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentSource;
import com.mopl.content.repository.ContentRepository;
import com.mopl.content.storage.ThumbnailStorage;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContentServiceImpl implements ContentService {

    private final ContentRepository contentRepository;
    private final ThumbnailStorage thumbnailStorage;

    @Override
    @Transactional
    public ContentDto create(ContentCreateRequest request, MultipartFile thumbnail) {
        Content content = Content.builder()
                .type(request.type())
                .source(ContentSource.MANUAL)
                .title(request.title())
                .description(request.description())
                .build();
        request.tags().forEach(content::addTag);
        content.updateThumbnail(thumbnailStorage.upload(thumbnail));
        return ContentDto.from(contentRepository.save(content));
    }

    @Override
    public ContentDto get(UUID contentId) {
        return ContentDto.from(findOrThrow(contentId));
    }

    @Override
    @Transactional
    public ContentDto update(UUID contentId, ContentUpdateRequest request, MultipartFile thumbnail) {
        Content content = findOrThrow(contentId);
        Set<String> tags = request.tags() != null ? new HashSet<>(request.tags()) : null;
        content.update(request.title(), request.description(), tags);
        if (thumbnail != null && !thumbnail.isEmpty()) {
            content.updateThumbnail(thumbnailStorage.upload(thumbnail));
        }
        return ContentDto.from(contentRepository.save(content));
    }

    @Override
    @Transactional
    public void delete(UUID contentId) {
        Content content = findOrThrow(contentId);
        contentRepository.delete(content);
    }

    private Content findOrThrow(UUID contentId) {
        return contentRepository.findById(contentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }
}