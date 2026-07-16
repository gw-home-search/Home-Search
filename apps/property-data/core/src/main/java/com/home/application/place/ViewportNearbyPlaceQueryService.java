package com.home.application.place;

import com.home.domain.place.NearbyPlaceCategory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class ViewportNearbyPlaceQueryService implements ViewportNearbyPlaceUseCase {

    private static final int MIN_LEVEL = 1;
    private static final int MAX_LEVEL = 4;
    private static final double MAX_DIAGONAL_METERS = 10_000;
    private static final int MAX_RETURNED_PLACES = 10;
    private static final double EARTH_RADIUS_METERS = 6_371_000;

    private final NearbyPlaceProvider provider;
    private final Clock clock;
    private final NearbyPlaceQueryExecutor queryExecutor;

    public ViewportNearbyPlaceQueryService(NearbyPlaceProvider provider, NearbyPlaceExecutionOptions options) {
        this.provider = Objects.requireNonNull(provider);
        NearbyPlaceExecutionOptions executionOptions = Objects.requireNonNull(options);
        this.clock = executionOptions.clock();
        this.queryExecutor = new NearbyPlaceQueryExecutor(executionOptions);
    }

    @Override
    public ViewportNearbyPlacesResult getNearbyPlaces(
            NearbyPlaceBounds bounds, Integer level, NearbyPlaceCategory category) {
        validate(bounds, level, category);
        NearbyPlaceBounds normalizedBounds = normalizeOutward(bounds);
        NearbyPlaceProviderQuery query = new NearbyPlaceProviderQuery(
                new NearbyPlaceBoundsArea(normalizedBounds.center(), normalizedBounds, level), category);
        NearbyPlaceProviderResult providerResult = queryExecutor.execute(() -> provider.search(query));
        if (providerResult == null || providerResult.category() != category || providerResult.retrievedAt() == null) {
            throw new NearbyPlaceProviderUnavailableException("nearby place provider category mismatch");
        }

        NearbyPlacePoint actualCenter = bounds.center();
        List<NearbyPlaceItem> places = providerResult.places() == null
                ? List.of()
                : providerResult.places().stream()
                        .filter(place -> bounds.contains(place.lat(), place.lng()))
                        .map(place -> place.withDistanceMeters((int) Math.round(
                                distanceMeters(actualCenter.lat(), actualCenter.lng(), place.lat(), place.lng()))))
                        .sorted(Comparator.comparingInt(NearbyPlaceItem::distanceMeters))
                        .limit(MAX_RETURNED_PLACES)
                        .toList();
        return new ViewportNearbyPlacesResult(
                bounds,
                level,
                clock.instant(),
                new ViewportNearbyPlaceCategoryResult(category, providerResult.retrievedAt(), places));
    }

    private void validate(NearbyPlaceBounds bounds, Integer level, NearbyPlaceCategory category) {
        if (bounds == null || level == null || category == null) {
            throw new InvalidNearbyPlaceRequestException("bounds, level and category are required");
        }
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            throw new InvalidNearbyPlaceRequestException("level must be between 1 and 4");
        }
        if (!finiteCoordinate(bounds.swLat(), -90, 90)
                || !finiteCoordinate(bounds.neLat(), -90, 90)
                || !finiteCoordinate(bounds.swLng(), -180, 180)
                || !finiteCoordinate(bounds.neLng(), -180, 180)
                || bounds.swLat() >= bounds.neLat()
                || bounds.swLng() >= bounds.neLng()) {
            throw new InvalidNearbyPlaceRequestException("bounds are invalid");
        }
        if (distanceMeters(bounds.swLat(), bounds.swLng(), bounds.neLat(), bounds.neLng()) > MAX_DIAGONAL_METERS) {
            throw new InvalidNearbyPlaceRequestException("bounds diagonal must not exceed 10km");
        }
    }

    private boolean finiteCoordinate(double value, double min, double max) {
        return Double.isFinite(value) && value >= min && value <= max;
    }

    private NearbyPlaceBounds normalizeOutward(NearbyPlaceBounds bounds) {
        return new NearbyPlaceBounds(
                grid(bounds.swLat(), RoundingMode.FLOOR),
                grid(bounds.swLng(), RoundingMode.FLOOR),
                grid(bounds.neLat(), RoundingMode.CEILING),
                grid(bounds.neLng(), RoundingMode.CEILING));
    }

    private double grid(double value, RoundingMode roundingMode) {
        return BigDecimal.valueOf(value).setScale(3, roundingMode).doubleValue();
    }

    private double distanceMeters(double fromLat, double fromLng, double toLat, double toLng) {
        double latDistance = Math.toRadians(toLat - fromLat);
        double lngDistance = Math.toRadians(toLng - fromLng);
        double startLat = Math.toRadians(fromLat);
        double endLat = Math.toRadians(toLat);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(startLat) * Math.cos(endLat) * Math.sin(lngDistance / 2) * Math.sin(lngDistance / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
