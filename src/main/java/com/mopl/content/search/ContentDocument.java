
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

    @Field(type = FieldType.Integer)
    private Integer reviewCount;

    // 검색/필터 대상이 아니라 표시용이라 색인하지 않는다.
    @Field(type = FieldType.Keyword, index = false)
    private String thumbnailUrl;

    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second_millis)
    private LocalDateTime createdAt;

    // createdAt(위)은 밀리초 정밀도라, Postgres contents.created_at(TIMESTAMP(6), 마이크로초)의
    // 원본 정밀도를 잃는다. 같은 밀리초에 여러 건이 생성돼도(배치 대량 삽입 등) 실제 생성 순서대로
    // 정렬하려면 마이크로초 단위 값이 필요해서 별도로 둔다. createdAt 정렬은 이 필드를 기준으로 한다.
    @Field(type = FieldType.Long)
    private Long createdAtEpochMicros;

    // _id 필드는 ES 7+에서 fielddata가 기본 비활성화라 정렬/search_after에 못 쓴다.
    // search_after로 id를 tie-breaker 정렬에 쓰기 위한 별도 keyword 필드.
    @Field(type = FieldType.Keyword)
    private String contentId;
}
