package com.home.infrastructure.web.read;

import com.home.application.read.ParcelDetailResult;
import com.home.application.read.PropertyReadUseCase;
import com.home.application.read.TradeListResult;
import com.home.application.read.TradeResult;
import com.home.infrastructure.web.read.dto.ParcelDetailResponse;
import com.home.infrastructure.web.read.dto.TradeListResponse;
import com.home.infrastructure.web.read.dto.TradeResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DetailController {

	private final PropertyReadUseCase readUseCase;

	public DetailController(PropertyReadUseCase readUseCase) {
		this.readUseCase = readUseCase;
	}

	@GetMapping("/api/v1/detail/{parcelId}")
	public ResponseEntity<ParcelDetailResponse> getParcelDetail(
		@PathVariable Long parcelId,
		@RequestParam(required = false) Long complexId
	) {
		return ResponseEntity.ok(toResponse(readUseCase.getParcelDetail(parcelId, complexId)));
	}

	@GetMapping("/api/v1/trade/{parcelId}")
	public ResponseEntity<TradeListResponse> getTradeList(
		@PathVariable Long parcelId,
		@RequestParam(required = false) Long complexId
	) {
		return ResponseEntity.ok(toResponse(readUseCase.getTradeList(parcelId, complexId)));
	}

	private static ParcelDetailResponse toResponse(ParcelDetailResult result) {
		return new ParcelDetailResponse(
			result.parcelId(),
			result.complexId(),
			result.latitude(),
			result.longitude(),
			result.address(),
			result.tradeName(),
			result.name(),
			result.dongCnt(),
			result.unitCnt(),
			result.platArea(),
			result.archArea(),
			result.totArea(),
			result.bcRat(),
			result.vlRat(),
			result.useDate()
		);
	}

	private static TradeListResponse toResponse(TradeListResult result) {
		return new TradeListResponse(
			result.parcelId(),
			result.complexId(),
			result.trades().stream()
				.map(DetailController::toResponse)
				.toList()
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
}
