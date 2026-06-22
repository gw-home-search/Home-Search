package com.home.news.infrastructure.persistence;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.domain.news.NewsModelDatasetTier;
import com.home.domain.news.NewsRegionBucket;
import com.home.domain.news.RegionMonthSignalEvidenceScope;
import com.home.domain.news.RegionMonthSignalSourceKind;
import com.home.news.application.NewsSignalValidationException;
import com.home.news.application.RegionMonthSignalEvidence;
import com.home.news.application.RegionMonthSignalSnapshot;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcRegionMonthSignalRepository {

	private final JdbcClient jdbcClient;
	private final ObjectMapper objectMapper;

	public JdbcRegionMonthSignalRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
		this.jdbcClient = jdbcClient;
		this.objectMapper = objectMapper;
	}

	public long startImportRun(RegionMonthSignalSourceKind sourceKind, String methodVersion, NewsModelDatasetTier datasetTier, String inputPath) {
		return jdbcClient.sql("""
			INSERT INTO news.region_month_signal_import_run (
			    source_kind,
			    method_version,
			    dataset_tier,
			    input_path,
			    status
			)
			VALUES (:sourceKind, :methodVersion, :datasetTier, :inputPath, 'RUNNING')
			RETURNING id
			""")
			.param("sourceKind", sourceKind.name())
			.param("methodVersion", methodVersion)
			.param("datasetTier", datasetTier.name())
			.param("inputPath", inputPath)
			.query(Long.class)
			.single();
	}

	public void finishImportRun(long id, int rowCount, int snapshotUpsertCount, int evidenceUpsertCount, String status, String failureReason) {
		jdbcClient.sql("""
			UPDATE news.region_month_signal_import_run
			SET row_count = :rowCount,
			    snapshot_upsert_count = :snapshotUpsertCount,
			    evidence_upsert_count = :evidenceUpsertCount,
			    status = :status,
			    failure_reason = :failureReason,
			    finished_at = now()
			WHERE id = :id
			""")
			.param("rowCount", rowCount)
			.param("snapshotUpsertCount", snapshotUpsertCount)
			.param("evidenceUpsertCount", evidenceUpsertCount)
			.param("status", status)
			.param("failureReason", failureReason)
			.param("id", id)
			.update();
	}

	public long upsertSnapshot(RegionMonthSignalSnapshot snapshot, long importRunId) {
		return jdbcClient.sql("""
			INSERT INTO news.region_month_signal_snapshot (
			    region_bucket,
			    signal_month,
			    source_kind,
			    method_version,
			    dataset_tier,
			    news_count,
			    matched_news_count,
			    direct_evidence_count,
			    inherited_evidence_count,
			    policy_positive_score,
			    policy_negative_score,
			    redevelopment_score,
			    transport_score,
			    supply_risk_score,
			    sale_market_score,
			    rental_market_score,
			    price_up_signal,
			    price_down_signal,
			    confidence,
			    aggregate_note,
			    import_run_id
			)
			VALUES (
			    :regionBucket,
			    :signalMonth,
			    :sourceKind,
			    :methodVersion,
			    :datasetTier,
			    :newsCount,
			    :matchedNewsCount,
			    :directEvidenceCount,
			    :inheritedEvidenceCount,
			    :policyPositiveScore,
			    :policyNegativeScore,
			    :redevelopmentScore,
			    :transportScore,
			    :supplyRiskScore,
			    :saleMarketScore,
			    :rentalMarketScore,
			    :priceUpSignal,
			    :priceDownSignal,
			    :confidence,
			    :aggregateNote,
			    :importRunId
			)
			ON CONFLICT (region_bucket, signal_month, method_version)
			DO UPDATE SET
			    source_kind = EXCLUDED.source_kind,
			    dataset_tier = EXCLUDED.dataset_tier,
			    news_count = EXCLUDED.news_count,
			    matched_news_count = EXCLUDED.matched_news_count,
			    direct_evidence_count = EXCLUDED.direct_evidence_count,
			    inherited_evidence_count = EXCLUDED.inherited_evidence_count,
			    policy_positive_score = EXCLUDED.policy_positive_score,
			    policy_negative_score = EXCLUDED.policy_negative_score,
			    redevelopment_score = EXCLUDED.redevelopment_score,
			    transport_score = EXCLUDED.transport_score,
			    supply_risk_score = EXCLUDED.supply_risk_score,
			    sale_market_score = EXCLUDED.sale_market_score,
			    rental_market_score = EXCLUDED.rental_market_score,
			    price_up_signal = EXCLUDED.price_up_signal,
			    price_down_signal = EXCLUDED.price_down_signal,
			    confidence = EXCLUDED.confidence,
			    aggregate_note = EXCLUDED.aggregate_note,
			    import_run_id = EXCLUDED.import_run_id,
			    updated_at = now()
			RETURNING id
			""")
			.param("regionBucket", snapshot.regionBucket().name())
			.param("signalMonth", snapshot.signalMonth())
			.param("sourceKind", snapshot.sourceKind().name())
			.param("methodVersion", snapshot.methodVersion())
			.param("datasetTier", snapshot.datasetTier().name())
			.param("newsCount", snapshot.newsCount())
			.param("matchedNewsCount", snapshot.matchedNewsCount())
			.param("directEvidenceCount", snapshot.directEvidenceCount())
			.param("inheritedEvidenceCount", snapshot.inheritedEvidenceCount())
			.param("policyPositiveScore", snapshot.policyPositiveScore())
			.param("policyNegativeScore", snapshot.policyNegativeScore())
			.param("redevelopmentScore", snapshot.redevelopmentScore())
			.param("transportScore", snapshot.transportScore())
			.param("supplyRiskScore", snapshot.supplyRiskScore())
			.param("saleMarketScore", snapshot.saleMarketScore())
			.param("rentalMarketScore", snapshot.rentalMarketScore())
			.param("priceUpSignal", snapshot.priceUpSignal())
			.param("priceDownSignal", snapshot.priceDownSignal())
			.param("confidence", snapshot.confidence())
			.param("aggregateNote", snapshot.aggregateNote())
			.param("importRunId", importRunId)
			.query(Long.class)
			.single();
	}

	public int replaceEvidence(long snapshotId, List<RegionMonthSignalEvidence> evidence) {
		jdbcClient.sql("DELETE FROM news.region_month_signal_evidence WHERE snapshot_id = :snapshotId")
			.param("snapshotId", snapshotId)
			.update();
		int count = 0;
		for (RegionMonthSignalEvidence item : evidence) {
			jdbcClient.sql("""
				INSERT INTO news.region_month_signal_evidence (
				    snapshot_id,
				    source_key,
				    title,
				    publisher,
				    published_date,
				    url,
				    citation_url,
				    topic_tags,
				    evidence_scope
				)
				VALUES (
				    :snapshotId,
				    :sourceKey,
				    :title,
				    :publisher,
				    :publishedDate,
				    :url,
				    :citationUrl,
				    CAST(:topicTags AS jsonb),
				    :evidenceScope
				)
				ON CONFLICT (snapshot_id, source_key) DO NOTHING
				""")
				.param("snapshotId", snapshotId)
				.param("sourceKey", item.sourceKey())
				.param("title", item.title())
				.param("publisher", item.publisher())
				.param("publishedDate", item.publishedDate())
				.param("url", item.url())
				.param("citationUrl", item.citationUrl())
				.param("topicTags", topicTagsJson(item.topicTags()))
				.param("evidenceScope", item.evidenceScope().name())
				.update();
			count++;
		}
		return count;
	}

	public List<RegionMonthSignalSnapshot> findAllSnapshots() {
		List<SnapshotRow> rows = jdbcClient.sql("""
			SELECT *
			FROM news.region_month_signal_snapshot
			ORDER BY signal_month, region_bucket
			""").query(this::mapSnapshotRow).list();
		List<RegionMonthSignalSnapshot> snapshots = new ArrayList<>();
		for (SnapshotRow row : rows) {
			snapshots.add(row.toSnapshot(findEvidence(row.id())));
		}
		return List.copyOf(snapshots);
	}

	public long snapshotCount() {
		return jdbcClient.sql("SELECT count(*) FROM news.region_month_signal_snapshot")
			.query(Long.class)
			.single();
	}

	private List<RegionMonthSignalEvidence> findEvidence(long snapshotId) {
		return jdbcClient.sql("""
			SELECT *
			FROM news.region_month_signal_evidence
			WHERE snapshot_id = :snapshotId
			ORDER BY id
			""")
			.param("snapshotId", snapshotId)
			.query(this::mapEvidence)
			.list();
	}

	private SnapshotRow mapSnapshotRow(ResultSet rs, int rowNum) throws SQLException {
		return new SnapshotRow(
			rs.getLong("id"),
			NewsRegionBucket.valueOf(rs.getString("region_bucket")),
			rs.getObject("signal_month", LocalDate.class),
			RegionMonthSignalSourceKind.valueOf(rs.getString("source_kind")),
			rs.getString("method_version"),
			NewsModelDatasetTier.valueOf(rs.getString("dataset_tier")),
			rs.getInt("news_count"),
			rs.getInt("matched_news_count"),
			rs.getInt("direct_evidence_count"),
			rs.getInt("inherited_evidence_count"),
			rs.getInt("policy_positive_score"),
			rs.getInt("policy_negative_score"),
			rs.getInt("redevelopment_score"),
			rs.getInt("transport_score"),
			rs.getInt("supply_risk_score"),
			rs.getInt("sale_market_score"),
			rs.getInt("rental_market_score"),
			rs.getInt("price_up_signal"),
			rs.getInt("price_down_signal"),
			rs.getBigDecimal("confidence"),
			rs.getString("aggregate_note")
		);
	}

	private RegionMonthSignalEvidence mapEvidence(ResultSet rs, int rowNum) throws SQLException {
		return new RegionMonthSignalEvidence(
			rs.getString("source_key"),
			rs.getString("title"),
			rs.getString("publisher"),
			rs.getObject("published_date", LocalDate.class),
			rs.getString("url"),
			rs.getString("citation_url"),
			topicTags(rs.getString("topic_tags")),
			RegionMonthSignalEvidenceScope.valueOf(rs.getString("evidence_scope"))
		);
	}

	private String topicTagsJson(List<String> tags) {
		try {
			return objectMapper.writeValueAsString(tags == null ? List.of() : tags);
		}
		catch (JsonProcessingException ex) {
			throw new NewsSignalValidationException("failed to serialize topic tags", ex);
		}
	}

	private List<String> topicTags(String json) {
		try {
			return objectMapper.readerForListOf(String.class).readValue(json);
		}
		catch (IOException ex) {
			throw new NewsSignalValidationException("failed to parse topic tags", ex);
		}
	}

	private record SnapshotRow(
		long id,
		NewsRegionBucket regionBucket,
		LocalDate signalMonth,
		RegionMonthSignalSourceKind sourceKind,
		String methodVersion,
		NewsModelDatasetTier datasetTier,
		int newsCount,
		int matchedNewsCount,
		int directEvidenceCount,
		int inheritedEvidenceCount,
		int policyPositiveScore,
		int policyNegativeScore,
		int redevelopmentScore,
		int transportScore,
		int supplyRiskScore,
		int saleMarketScore,
		int rentalMarketScore,
		int priceUpSignal,
		int priceDownSignal,
		BigDecimal confidence,
		String aggregateNote
	) {
		RegionMonthSignalSnapshot toSnapshot(List<RegionMonthSignalEvidence> evidence) {
			return new RegionMonthSignalSnapshot(
				regionBucket,
				signalMonth,
				sourceKind,
				methodVersion,
				datasetTier,
				newsCount,
				matchedNewsCount,
				directEvidenceCount,
				inheritedEvidenceCount,
				policyPositiveScore,
				policyNegativeScore,
				redevelopmentScore,
				transportScore,
				supplyRiskScore,
				saleMarketScore,
				rentalMarketScore,
				priceUpSignal,
				priceDownSignal,
				confidence,
				aggregateNote,
				evidence
			);
		}
	}
}
