package com.home.infrastructure.web.read;

import com.home.application.read.PropertyReadUseCase;
import com.home.infrastructure.web.read.dto.ParcelDetailResponse;
import com.home.infrastructure.web.read.dto.TradeListResponse;

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
		return ResponseEntity.ok(readUseCase.getParcelDetail(parcelId, complexId));
	}

	@GetMapping("/api/v1/trade/{parcelId}")
	public ResponseEntity<TradeListResponse> getTradeList(
		@PathVariable Long parcelId,
		@RequestParam(required = false) Long complexId
	) {
		return ResponseEntity.ok(readUseCase.getTradeList(parcelId, complexId));
	}
}
