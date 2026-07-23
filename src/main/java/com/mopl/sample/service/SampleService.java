package com.mopl.sample.service;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.sample.dto.SampleCreateRequest;
import com.mopl.sample.dto.SampleDto;
import com.mopl.sample.entity.Sample;
import com.mopl.sample.repository.SampleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 서비스 계층을 어떻게 짜는지 보여 주는 예시입니다.
 * 트랜잭션 경계(@Transactional)와 없는 리소스에 대한 BusinessException을 담고 있습니다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SampleService {

    private final SampleRepository sampleRepository;

    @Transactional
    public SampleDto create(SampleCreateRequest request) {
        Sample saved = sampleRepository.save(Sample.builder().name(request.name()).build());
        return SampleDto.from(saved);
    }

    public SampleDto get(UUID id) {
        Sample sample = sampleRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return SampleDto.from(sample);
    }
}
