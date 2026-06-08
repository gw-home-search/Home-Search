package com.home.infrastructure.persistence.news;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.news.signal.NewsSignalDatasetRepository;
import com.home.application.news.signal.NewsSignalDatasetRow;
import com.home.application.news.signal.NewsSignalFeatureExtractionPolicy;

import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcNewsSignalDatasetRepository implements NewsSignalDatasetRepository {

	private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
	};
	private static final TypeReference<List<Map<String, Object>>> MAP_LIST = new TypeReference<>() {
	};

	private final JdbcClient jdbcClient;
	private final ObjectMapper objectMapper;

	JdbcNewsSignalDatasetRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
		this.jdbcClient = jdbcClient;
		this.objectMapper = objectMapper;
	}

	@Override
	public List<NewsSignalDatasetRow> findAtOrBefore(OffsetDateTime predictionCutoff, int limit) {
		if (limit < 1) {
			return List.of();
		}
		return jdbcClient.sql("""
			SELECT
			    feature_id,
			    article_observation_id,
			    source,
			    source_key,
			    publisher,
			    title,
			    url,
			    provider_url,
			    snippet,
			    published_at,
			    provider_pub_at,
			    first_seen_at,
			    feature_date_kst,
			    news_date_kst,
			    article_collected_at,
			    title_keywords::text AS title_keywords,
			    region_tags::text AS region_tags,
			    complex_candidates::text AS complex_candidates,
			    topic_tags::text AS topic_tags,
			    impact_target,
			    impact_direction,
			    sentiment,
			    confidence,
			    extraction_version,
			    evidence_level,
			    feature_created_at
			FROM news_signal_dataset_view
			WHERE first_seen_at <= :predictionCutoff
			  AND extraction_version = :extractionVersion
			ORDER BY first_seen_at, feature_id
			LIMIT :limit
			""")
			.param("predictionCutoff", predictionCutoff)
			.param("extractionVersion", NewsSignalFeatureExtractionPolicy.DEFAULT_EXTRACTION_VERSION)
			.param("limit", limit)
			.query((resultSet, rowNumber) -> new NewsSignalDatasetRow(
				resultSet.getLong("feature_id"),
				resultSet.getLong("article_observation_id"),
				resultSet.getString("source"),
				resultSet.getString("source_key"),
				resultSet.getString("publisher"),
				resultSet.getString("title"),
				resultSet.getString("url"),
				resultSet.getString("provider_url"),
				resultSet.getString("snippet"),
				resultSet.getObject("published_at", OffsetDateTime.class),
				resultSet.getObject("provider_pub_at", OffsetDateTime.class),
				resultSet.getObject("first_seen_at", OffsetDateTime.class),
				resultSet.getObject("feature_date_kst", LocalDate.class),
				resultSet.getObject("news_date_kst", LocalDate.class),
				resultSet.getObject("article_collected_at", OffsetDateTime.class),
				readStringList(resultSet.getString("title_keywords")),
				readStringList(resultSet.getString("region_tags")),
				readMapList(resultSet.getString("complex_candidates")),
				readStringList(resultSet.getString("topic_tags")),
				resultSet.getString("impact_target"),
				resultSet.getString("impact_direction"),
				resultSet.getString("sentiment"),
				resultSet.getDouble("confidence"),
				resultSet.getString("extraction_version"),
				resultSet.getString("evidence_level"),
				resultSet.getObject("feature_created_at", OffsetDateTime.class)
			))
			.list();
	}

	private List<String> readStringList(String value) {
		return readJson(value, STRING_LIST);
	}

	private List<Map<String, Object>> readMapList(String value) {
		return readJson(value, MAP_LIST);
	}

	private <T> T readJson(String value, TypeReference<T> typeReference) {
		try {
			return objectMapper.readValue(value, typeReference);
		}
		catch (IOException exception) {
			throw new IllegalStateException("Failed to deserialize news signal dataset JSON", exception);
		}
	}
}
