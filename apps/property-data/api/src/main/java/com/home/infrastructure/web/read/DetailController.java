package com.home.infrastructure.web.read;

import java.util.List;

import com.home.application.read.ComplexSummaryResult;
import com.home.application.read.ParcelDetailResult;
import com.home.application.read.PropertyReadUseCase;
import com.home.application.read.TradeListResult;
import com.home.application.read.TradeResult;
import com.home.application.read.TradeTrendPoint;
import com.home.application.prediction.PricePredictionUseCase;
import com.home.infrastructure.web.read.dto.ComplexSummaryResponse;
import com.home.infrastructure.web.read.dto.PageResponse;
import com.home.infrastructure.web.read.dto.ParcelDetailResponse;
import com.home.infrastructure.web.read.dto.PricePredictionResponse;
import com.home.infrastructure.web.read.dto.TradeListResponse;
import com.home.infrastructure.web.read.dto.TradeResponse;
import com.home.infrastructure.web.read.dto.TradeTrendResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DetailController {

	private static final Logger log = LoggerFactory.getLogger(DetailController.class);

	private final PropertyReadUseCase readUseCase;
	private final ObjectProvider<PricePredictionUseCase> predictionUseCaseProvider;

	public DetailController(
		PropertyReadUseCase readUseCase,
		ObjectProvider<PricePredictionUseCase> predictionUseCaseProvider
	) {
		this.readUseCase = readUseCase;
		this.predictionUseCaseProvider = predictionUseCaseProvider;
	}

	@GetMapping("/api/v1/detail/{parcelId}")
	public ResponseEntity<ParcelDetailResponse> getParcelDetail(
		@PathVariable Long parcelId,
		@RequestParam(required = false) Long complexId
	) {
		ParcelDetailResult result = readUseCase.getParcelDetail(parcelId, complexId);
		return ResponseEntity.ok(toResponse(result, predictionResponse(result.complexId())));
	}

	@GetMapping("/api/v1/detail/{parcelId}/complexes")
	public ResponseEntity<List<ComplexSummaryResponse>> getParcelComplexes(@PathVariable Long parcelId) {
		return ResponseEntity.ok(readUseCase.getParcelComplexes(parcelId)
			.stream()
			.map(DetailController::toResponse)
			.toList());
	}

	@GetMapping("/api/v1/trade/{parcelId}")
	public ResponseEntity<TradeListResponse> getTradeList(
		@PathVariable Long parcelId,
		@RequestParam(required = false) Long complexId,
		@RequestParam(required = false) Integer page,
		@RequestParam(required = false) Integer size
	) {
		return ResponseEntity.ok(toResponse(readUseCase.getTradeList(parcelId, complexId, page, size)));
	}

	@GetMapping("/api/v1/complex/{complexId}")
	public ResponseEntity<ParcelDetailResponse> getComplexDetail(@PathVariable Long complexId) {
		ParcelDetailResult result = readUseCase.getComplexDetail(complexId);
		return ResponseEntity.ok(toResponse(result, predictionResponse(result.complexId())));
	}

	@GetMapping("/api/v1/complex/{complexId}/trades")
	public ResponseEntity<TradeListResponse> getComplexTradeList(
		@PathVariable Long complexId,
		@RequestParam(required = false) Integer page,
		@RequestParam(required = false) Integer size
	) {
		return ResponseEntity.ok(toResponse(readUseCase.getComplexTradeList(complexId, page, size)));
	}

	@GetMapping("/api/v1/trade/{parcelId}/trend")
	public ResponseEntity<List<TradeTrendResponse>> getTradeTrend(
		@PathVariable Long parcelId,
		@RequestParam(required = false) Long complexId
	) {
		return ResponseEntity.ok(readUseCase.getTradeTrend(parcelId, complexId)
			.stream()
			.map(DetailController::toResponse)
			.toList());
	}

	@GetMapping("/api/v1/complex/{complexId}/trade-trend")
	public ResponseEntity<List<TradeTrendResponse>> getComplexTradeTrend(@PathVariable Long complexId) {
		return ResponseEntity.ok(readUseCase.getComplexTradeTrend(complexId)
			.stream()
			.map(DetailController::toResponse)
			.toList());
	}

	private static ComplexSummaryResponse toResponse(ComplexSummaryResult result) {
		return new ComplexSummaryResponse(
			result.complexId(),
			result.complexName(),
			result.parcelId(),
			result.latitude(),
			result.longitude(),
			result.address(),
			result.dongCnt(),
			result.unitCnt(),
			result.useDate()
		);
	}

	private PricePredictionResponse predictionResponse(Long complexId) {
		PricePredictionUseCase predictionUseCase = predictionUseCaseProvider.getIfAvailable();
		if (predictionUseCase == null || complexId == null) {
			return null;
		}
		try {
			return PricePredictionResponse.from(predictionUseCase.getOrSchedulePrediction(complexId));
		} catch (RuntimeException ex) {
			log.debug("Failed to build prediction response complexId={}", complexId, ex);
			return PricePredictionResponse.failed("AI prediction unavailable");
		}
	}

	private static ParcelDetailResponse toResponse(ParcelDetailResult result, PricePredictionResponse prediction) {
		return new ParcelDetailResponse(
			result.parcelId(),
			result.complexId(),
			result.latitude(),
			result.longitude(),
			result.address(),
			result.displayName(),
			result.tradeName(),
			result.name(),
			result.dongCnt(),
			result.unitCnt(),
			result.platArea(),
			result.archArea(),
			result.totArea(),
			result.bcRat(),
			result.vlRat(),
			result.useDate(),
			prediction
		);
	}

	private static TradeListResponse toResponse(TradeListResult result) {
		List<TradeResponse> content = result.trades().stream()
			.map(DetailController::toResponse)
			.toList();
		return new TradeListResponse(
			result.parcelId(),
			result.complexId(),
			PageResponse.of(content, result.page(), result.size(), result.totalElements())
		);
	}

	private static TradeResponse toResponse(TradeResult result) {
		return new TradeResponse(
			result.tradeId(),
			result.dealDate(),
			result.exclArea(),
			result.dealAmount(),
			result.aptDong(),
			result.floor()
		);
	}

	private static TradeTrendResponse toResponse(TradeTrendPoint point) {
		return new TradeTrendResponse(
			point.month(),
			point.avgAmount(),
			point.count(),
			point.minAmount(),
			point.maxAmount()
		);
	}

}
