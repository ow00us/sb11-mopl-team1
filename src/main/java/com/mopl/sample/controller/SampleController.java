package com.mopl.sample.controller;

import com.mopl.sample.dto.SampleCreateRequest;
import com.mopl.sample.dto.SampleDto;
import com.mopl.sample.service.SampleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 컨트롤러를 어떻게 짜는지 보여 주는 예시입니다.
 * 우리 규약대로 생성은 201, 단건 조회는 200으로 응답합니다.
 */
@RestController
@RequestMapping("/api/samples")
@RequiredArgsConstructor
public class SampleController {

    private final SampleService sampleService;

    @PostMapping
    public ResponseEntity<SampleDto> create(@Valid @RequestBody SampleCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sampleService.create(request));
    }

    @GetMapping("/{id}")
    public SampleDto get(@PathVariable UUID id) {
        return sampleService.get(id);
    }
}
