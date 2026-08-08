package com.mopl.user.storage;

import org.springframework.web.multipart.MultipartFile;

/**
 * 프로필 이미지 파일 저장 기능을 추상화한 인터페이스
 *
 * UserService는 이미지가 로컬에 저장되는지, S3에 저장되는지 알 필요 없이
 * 이 인터페이스의 upload 메서드만 사용
 *
 * 이후 실제 이미지 저장소가 정해지면 구현체만 교체할 수 있다.
 */
public interface ProfileImageStorage {

    /**
     * 전달받은 프로필 이미지 파일을 저장하고 접근 가능한 URL을 반환
     *
     * @param image 저장할 프로필 이미지 파일
     * @return 저장된 이미지에 접근할 수 있는 URL
     */
    String upload(MultipartFile image);
}
