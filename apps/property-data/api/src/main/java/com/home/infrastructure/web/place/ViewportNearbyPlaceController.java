package com.home.infrastructure.web.place;

import com.home.application.place.NearbyPlaceBounds;
import com.home.application.place.ViewportNearbyPlaceUseCase;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/map")
public class ViewportNearbyPlaceController {

    private final ViewportNearbyPlaceUseCase useCase;

    public ViewportNearbyPlaceController(ViewportNearbyPlaceUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping("/nearby-places")
    public ResponseEntity<ViewportNearbyPlacesResponse> getNearbyPlaces(
            @Valid @RequestBody ViewportNearbyPlaceRequest request) {
        NearbyPlaceBounds bounds =
                new NearbyPlaceBounds(request.swLat(), request.swLng(), request.neLat(), request.neLng());
        return ResponseEntity.ok(ViewportNearbyPlacesResponse.from(
                useCase.getNearbyPlaces(bounds, request.level(), request.category())));
    }
}
