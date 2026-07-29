package com.mopl.content.service;

import com.mopl.content.dto.ContentCreateRequest;
import com.mopl.content.dto.ContentDto;
import com.mopl.content.dto.ContentUpdateRequest;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

public interface ContentService {

    ContentDto create(ContentCreateRequest request, MultipartFile thumbnail);

    ContentDto get(UUID contentId);

    ContentDto update(UUID contentId, ContentUpdateRequest request, MultipartFile thumbnail);

    void delete(UUID contentId);
}