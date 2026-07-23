package com.mopl.sample.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.sample.dto.SampleCreateRequest;
import com.mopl.sample.dto.SampleDto;
import com.mopl.sample.entity.Sample;
import com.mopl.sample.repository.SampleRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SampleServiceTest {

    @Mock
    SampleRepository sampleRepository;

    @InjectMocks
    SampleService sampleService;

    @Test
    @DisplayName("샘플 생성 성공 시 저장하고 SampleDto를 반환")
    void create_success() {
        // given
        UUID sampleId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        Instant createdAt = Instant.parse("2026-07-23T03:00:00Z");
        SampleCreateRequest request = new SampleCreateRequest("sample");

        when(sampleRepository.save(any(Sample.class))).thenAnswer(invocation -> {
            Sample sample = invocation.getArgument(0);
            ReflectionTestUtils.setField(sample, "id", sampleId);
            ReflectionTestUtils.setField(sample, "createdAt", createdAt);
            return sample;
        });

        // when
        SampleDto response = sampleService.create(request);

        // then
        assertThat(response.id()).isEqualTo(sampleId);
        assertThat(response.name()).isEqualTo("sample");
        assertThat(response.createdAt()).isEqualTo(createdAt);

        ArgumentCaptor<Sample> sampleCaptor = ArgumentCaptor.forClass(Sample.class);
        verify(sampleRepository).save(sampleCaptor.capture());
        assertThat(sampleCaptor.getValue().getName()).isEqualTo("sample");
    }

    @Test
    @DisplayName("조회 대상 샘플이 없으면 예외가 발생")
    void get_fail_whenSampleNotFound() {
        // given
        UUID sampleId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        when(sampleRepository.findById(sampleId)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> sampleService.get(sampleId))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);

        verify(sampleRepository).findById(sampleId);
    }
}
