package com.mopl.content.storage;

import org.springframework.web.multipart.MultipartFile;

public interface ThumbnailStorage {
    String upload(MultipartFile file);
}