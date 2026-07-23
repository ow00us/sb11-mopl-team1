package com.mopl.sample.repository;

import com.mopl.sample.entity.Sample;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SampleRepository extends JpaRepository<Sample, UUID> {
}
