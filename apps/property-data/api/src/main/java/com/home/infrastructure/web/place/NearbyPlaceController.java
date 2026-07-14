package com.home.infrastructure.web.place;

import com.home.application.place.NearbyPlaceUseCase;
import com.home.domain.place.NearbyPlaceCategory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
public class NearbyPlaceController {

    private final NearbyPlaceUseCase nearbyPlaceUseCase;

    public NearbyPlaceController(NearbyPlaceUseCase nearbyPlaceUseCase) {
        this.nearbyPlaceUseCase = nearbyPlaceUseCase;
    }

    @GetMapping("/api/v1/complex/{complexId}/nearby-places")
    public ResponseEntity<NearbyPlacesResponse> getNearbyPlaces(
            @PathVariable @Positive Long complexId,
            @RequestParam(defaultValue = "800") @Min(100) @Max(2_000) Integer radiusMeters,
            @RequestParam(required = false) List<NearbyPlaceCategory> categories,
            @RequestParam(defaultValue = "5") @Min(1) @Max(15) Integer limitPerCategory) {
        return ResponseEntity.ok(NearbyPlacesResponse.from(
                nearbyPlaceUseCase.getNearbyPlaces(complexId, radiusMeters, categories, limitPerCategory)));
    }
}
