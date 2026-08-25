package com.mopl.global.storage;

import org.springframework.web.multipart.MultipartFile;

/**
 * 이미지 파일을 객체 저장소에 올리고 조회 가능한 URL 을 돌려줍니다.
 *
 * <p>프로필 이미지와 콘텐츠 썸네일은 저장 경로만 다르고 검증, 키 생성, 업로드 규칙이 같습니다.
 * 도메인마다 따로 구현하면 그 규칙이 갈라지고, 한쪽만 고쳐진 채 남습니다.
 */
public interface ImageObjectStorage {

    /**
     * 이미지를 올리고 조회 URL 을 돌려줍니다.
     *
     * @param file 올릴 파일
     * @param prefix 객체 키 앞에 붙일 구분자. 도메인이 정합니다
     * @return 조회 가능한 절대 URL
     * @throws ImageUploadException 검증에 실패했거나 올리지 못한 경우
     */
    String upload(MultipartFile file, String prefix);
}
