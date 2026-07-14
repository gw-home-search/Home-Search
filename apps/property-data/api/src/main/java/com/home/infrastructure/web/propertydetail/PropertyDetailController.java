package com.home.infrastructure.web.propertydetail;

import com.home.application.prediction.PricePredictionUseCase;
import com.home.application.propertydetail.PropertyDetailService;
import com.home.application.read.ParcelDetailResult;
import com.home.infrastructure.web.read.dto.ComplexSummaryResponse;
import com.home.infrastructure.web.read.dto.ParcelDetailResponse;
import com.home.infrastructure.web.read.dto.PricePredictionResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PropertyDetailController {

    private static final Logger log = LoggerFactory.getLogger(PropertyDetailController.class);

    private final PropertyDetailService propertyDetailService;
    private final ObjectProvider<PricePredictionUseCase> predictionUseCaseProvider;

    public PropertyDetailController(
            PropertyDetailService propertyDetailService,
            ObjectProvider<PricePredictionUseCase> predictionUseCaseProvider) {
        this.propertyDetailService = propertyDetailService;
        this.predictionUseCaseProvider = predictionUseCaseProvider;
    }

    @GetMapping("/api/v1/detail/{parcelId}")
    public ResponseEntity<ParcelDetailResponse> getParcelDetail(
            @PathVariable Long parcelId, @RequestParam(required = false) Long complexId) {
        ParcelDetailResult result = propertyDetailService.getParcelDetail(parcelId, complexId);
        return ResponseEntity.ok(ParcelDetailResponse.from(result, predictionResponse(result.complexId())));
    }

    @GetMapping("/api/v1/detail/{parcelId}/complexes")
    public ResponseEntity<List<ComplexSummaryResponse>> getParcelComplexes(@PathVariable Long parcelId) {
        return ResponseEntity.ok(propertyDetailService.getParcelComplexes(parcelId).stream()
                .map(ComplexSummaryResponse::from)
                .toList());
    }

    @GetMapping("/api/v1/complex/{complexId}")
    public ResponseEntity<ParcelDetailResponse> getComplexDetail(@PathVariable Long complexId) {
        ParcelDetailResult result = propertyDetailService.getComplexDetail(complexId);
        return ResponseEntity.ok(ParcelDetailResponse.from(result, predictionResponse(result.complexId())));
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
}
