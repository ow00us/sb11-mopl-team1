package com.mopl.content.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Converter
public class ContentSourceConverter implements AttributeConverter<ContentSource, String> {

    @Override
    public String convertToDatabaseColumn(ContentSource attribute) {
        return attribute == null ? null : attribute.name();
    }

    @Override
    public ContentSource convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        try {
            return ContentSource.valueOf(dbData);
        } catch (IllegalArgumentException e) {
            log.warn("알 수 없는 contents.source 값 '{}'을 만나 null로 대체합니다.", dbData);
            return null;
        }
    }
}