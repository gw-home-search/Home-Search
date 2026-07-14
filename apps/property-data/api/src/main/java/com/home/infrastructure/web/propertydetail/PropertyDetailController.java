package com.home.infrastructure.web.propertydetail;

import com.home.application.prediction.PricePredictionUseCase;
import com.home.application.propertydetail.PropertyDetailService;
import com.home.application.read.ParcelDetailResult;
import com.home.infrastructure.web.read.dto.ComplexSummaryResponse;
import com.home.infrastructure.web.read.dto.ParcelDetailResponse;
import com.home.infrastructure.web.read.dto.PricePredictionResponse;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class PropertyDetailController {

    private static final Logger log = LoggerFactory.getLogger(PropertyDetailController.class);

    private final PropertyDetailService propertyDetailService;
    private final PricePredictionUseCase predictionUseCase;

    public PropertyDetailController(
            PropertyDetailService propertyDetailService, PricePredictionUseCase predictionUseCase) {
        this.propertyDetailService = propertyDetailService;
        this.predictionUseCase = predictionUseCase;
    }

    @GetMapping("/api/v1/detail/{parcelId}")
    public ResponseEntity<ParcelDetailResponse> getParcelDetail(
            @PathVariable @Positive Long parcelId, @RequestParam(required = false) @Positive Long complexId) {
        ParcelDetailResult result = propertyDetailService.getParcelDetail(parcelId, complexId);
        return ResponseEntity.ok(ParcelDetailResponse.from(result, predictionResponse(result.complexId())));
    }

    @GetMapping("/api/v1/detail/{parcelId}/complexes")
    public ResponseEntity<List<ComplexSummaryResponse>> getParcelComplexes(@PathVariable @Positive Long parcelId) {
        return ResponseEntity.ok(propertyDetailService.getParcelComplexes(parcelId).stream()
                .map(ComplexSummaryResponse::from)
                .toList());
    }

    @GetMapping("/api/v1/complex/{complexId}")
    public ResponseEntity<ParcelDetailResponse> getComplexDetail(@PathVariable @Positive Long complexId) {
        ParcelDetailResult result = propertyDetailService.getComplexDetail(complexId);
        return ResponseEntity.ok(ParcelDetailResponse.from(result, predictionResponse(result.complexId())));
    }

    private PricePredictionResponse predictionResponse(Long complexId) {
        if (complexId == null) {
            return null;
        }
        try {
            return PricePredictionResponse.from(predictionUseCase.getOrSchedulePrediction(complexId));
        } catch (RuntimeException ex) {
            log.debug("Prediction response degraded type={}", ex.getClass().getSimpleName());
            return PricePredictionResponse.failed("AI prediction unavailable");
        }
    }
}
