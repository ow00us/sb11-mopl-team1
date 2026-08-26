package com.mopl.content.search;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface ContentSearchRepository extends ElasticsearchRepository<ContentDocument, String> {
}
