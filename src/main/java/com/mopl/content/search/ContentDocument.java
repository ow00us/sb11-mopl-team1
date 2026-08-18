
package com.mopl.content.search;

import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Setting;

@Getter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "contents")
@Setting(settingPath = "elasticsearch/content-settings.json")
public class ContentDocument {

    @Id
    private String id;

    @Field(type = FieldType.Text, analyzer = "korean_nori")
    private String title;

    @Field(type = FieldType.Text, analyzer = "korean_nori")
    private String description;

    @Field(type = FieldType.Keyword)
    private String type;

    @Field(type = FieldType.Keyword)
    private List<String> tags;

    @Field(type = FieldType.Double)
    private Double averageRating;

    @Field(type = FieldType.Integer)
    private Integer watcherCount;

    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second_millis)
    private LocalDateTime createdAt;
}
