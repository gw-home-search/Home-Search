package com.home.application.ingest.trade;

import com.home.application.ingest.matching.ComplexMasterBootstrapper;
import com.home.application.ingest.matching.ComplexMatcher;
import com.home.application.ingest.matching.TradeMatchEvidenceRepository;
import com.home.application.ingest.normalization.NormalizedTradeRepository;
import com.home.application.ingest.raw.RawTradeIngestRepository;

public final class OpenApiTradeIngestServiceFixture {

	private OpenApiTradeIngestServiceFixture() {
	}

	public static OpenApiTradeIngestService service(
		RawTradeIngestRepository rawTradeIngestRepository,
		NormalizedTradeRepository normalizedTradeRepository,
		ComplexMatcher complexMatcher
	) {
		return service(
			rawTradeIngestRepository,
			normalizedTradeRepository,
			complexMatcher,
			ComplexMasterBootstrapper.noop()
		);
	}

	public static OpenApiTradeIngestService service(
		RawTradeIngestRepository rawTradeIngestRepository,
		NormalizedTradeRepository normalizedTradeRepository,
		ComplexMatcher complexMatcher,
		ComplexMasterBootstrapper complexMasterBootstrapper
	) {
		return service(
			rawTradeIngestRepository,
			normalizedTradeRepository,
			complexMatcher,
			complexMasterBootstrapper,
			TradeMatchEvidenceRepository.noop()
		);
	}

	public static OpenApiTradeIngestService service(
		RawTradeIngestRepository rawTradeIngestRepository,
		NormalizedTradeRepository normalizedTradeRepository,
		ComplexMatcher complexMatcher,
		ComplexMasterBootstrapper complexMasterBootstrapper,
		TradeMatchEvidenceRepository tradeMatchEvidenceRepository
	) {
		return service(
			rawTradeIngestRepository,
			normalizedTradeRepository,
			complexMatcher,
			complexMasterBootstrapper,
			TradeIngestMetrics.noop(),
			tradeMatchEvidenceRepository
		);
	}

	public static OpenApiTradeIngestService service(
		RawTradeIngestRepository rawTradeIngestRepository,
		NormalizedTradeRepository normalizedTradeRepository,
		ComplexMatcher complexMatcher,
		ComplexMasterBootstrapper complexMasterBootstrapper,
		TradeIngestMetrics tradeIngestMetrics
	) {
		return service(
			rawTradeIngestRepository,
			normalizedTradeRepository,
			complexMatcher,
			complexMasterBootstrapper,
			tradeIngestMetrics,
			TradeMatchEvidenceRepository.noop()
		);
	}

	public static OpenApiTradeIngestService service(
		RawTradeIngestRepository rawTradeIngestRepository,
		NormalizedTradeRepository normalizedTradeRepository,
		ComplexMatcher complexMatcher,
		ComplexMasterBootstrapper complexMasterBootstrapper,
		TradeIngestMetrics tradeIngestMetrics,
		TradeMatchEvidenceRepository tradeMatchEvidenceRepository
	) {
		TradeIngestItemProcessor itemProcessor = new TradeIngestItemProcessor(
			rawTradeIngestRepository,
			normalizedTradeRepository,
			complexMatcher,
			complexMasterBootstrapper,
			tradeMatchEvidenceRepository
		);
		return new OpenApiTradeIngestService(itemProcessor, tradeIngestMetrics);
	}
}
