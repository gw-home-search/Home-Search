package com.home.application.place;

import com.home.application.read.ResourceNotFoundException;
import com.home.domain.place.NearbyPlaceCategory;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class NearbyPlaceQueryService implements NearbyPlaceUseCase {

    private static final int DEFAULT_RADIUS_METERS = 800;
    private static final int MIN_RADIUS_METERS = 100;
    private static final int MAX_RADIUS_METERS = 2_000;
    private static final int DEFAULT_LIMIT_PER_CATEGORY = 5;
    private static final int MAX_LIMIT_PER_CATEGORY = 15;
    private static final List<NearbyPlaceCategory> SUPPORTED_CATEGORIES = List.of(NearbyPlaceCategory.values());
    private static final List<NearbyPlaceCategory> DEFAULT_CATEGORIES = List.of(
            NearbyPlaceCategory.SUPERMARKET,
            NearbyPlaceCategory.CONVENIENCE_STORE,
            NearbyPlaceCategory.RESTAURANT,
            NearbyPlaceCategory.DAYCARE_KINDERGARTEN,
            NearbyPlaceCategory.SCHOOL,
            NearbyPlaceCategory.ACADEMY,
            NearbyPlaceCategory.SUBWAY_STATION,
            NearbyPlaceCategory.HOSPITAL);

    private final NearbyPlaceCenterReader centerReader;
    private final NearbyPlaceProvider provider;
    private final NearbyPlaceQueryExecutor queryExecutor;
    private final Clock clock;

    public NearbyPlaceQueryService(
            NearbyPlaceCenterReader centerReader,
            NearbyPlaceProvider provider,
            NearbyPlaceExecutionOptions executionOptions) {
        this.centerReader = Objects.requireNonNull(centerReader);
        this.provider = Objects.requireNonNull(provider);
        NearbyPlaceExecutionOptions options = Objects.requireNonNull(executionOptions);
        this.queryExecutor = new NearbyPlaceQueryExecutor(options);
        this.clock = options.clock();
    }

    @Override
    public NearbyPlacesResult getNearbyPlaces(
            Long complexId,
            Integer requestedRadiusMeters,
            List<NearbyPlaceCategory> requestedCategories,
            Integer requestedLimitPerCategory) {
        if (complexId == null || complexId < 1) {
            throw new InvalidNearbyPlaceRequestException("complexId must be positive");
        }
        int radiusMeters = normalizeRadius(requestedRadiusMeters);
        int limitPerCategory = normalizeLimit(requestedLimitPerCategory);
        List<NearbyPlaceCategory> categories = normalizeCategories(requestedCategories);
        NearbyPlaceCenter center = centerReader
                .findComplexCenter(complexId)
                .orElseThrow(() -> new ResourceNotFoundException("complex not found: " + complexId));
        NearbyPlacePoint point = requiredPoint(center);

        List<Supplier<NearbyPlaceProviderResult>> tasks = categories.stream()
                .<Supplier<NearbyPlaceProviderResult>>map(category -> () -> provider.search(
                        new NearbyPlaceProviderQuery(new NearbyPlaceRadiusArea(point, radiusMeters), category)))
                .toList();
        List<NearbyPlaceProviderResult> providerResults = queryExecutor.executeAll(tasks);
        List<NearbyPlaceCategoryResult> results = new ArrayList<>(providerResults.size());
        for (int index = 0; index < providerResults.size(); index++) {
            NearbyPlaceProviderResult providerResult = providerResults.get(index);
            NearbyPlaceCategory expectedCategory = categories.get(index);
            if (providerResult == null || providerResult.category() != expectedCategory) {
                throw new NearbyPlaceProviderUnavailableException("nearby place provider category mismatch");
            }
            List<NearbyPlaceItem> places = providerResult.places() == null
                    ? List.of()
                    : providerResult.places().stream()
                            .sorted(Comparator.comparingInt(NearbyPlaceItem::distanceMeters))
                            .limit(limitPerCategory)
                            .toList();
            int matchedCount = Math.max(0, providerResult.matchedCount());
            results.add(new NearbyPlaceCategoryResult(
                    expectedCategory,
                    matchedCount,
                    places.size(),
                    matchedCount > places.size(),
                    Objects.requireNonNull(providerResult.retrievedAt()),
                    places));
        }

        return new NearbyPlacesResult(complexId, point, radiusMeters, clock.instant(), List.copyOf(results));
    }

    private int normalizeRadius(Integer requestedRadiusMeters) {
        int radiusMeters = requestedRadiusMeters == null ? DEFAULT_RADIUS_METERS : requestedRadiusMeters;
        if (radiusMeters < MIN_RADIUS_METERS || radiusMeters > MAX_RADIUS_METERS) {
            throw new InvalidNearbyPlaceRequestException("radiusMeters must be between 100 and 2000");
        }
        return radiusMeters;
    }

    private int normalizeLimit(Integer requestedLimitPerCategory) {
        int limit = requestedLimitPerCategory == null ? DEFAULT_LIMIT_PER_CATEGORY : requestedLimitPerCategory;
        if (limit < 1 || limit > MAX_LIMIT_PER_CATEGORY) {
            throw new InvalidNearbyPlaceRequestException("limitPerCategory must be between 1 and 15");
        }
        return limit;
    }

    private List<NearbyPlaceCategory> normalizeCategories(List<NearbyPlaceCategory> requestedCategories) {
        if (requestedCategories == null || requestedCategories.isEmpty()) {
            return DEFAULT_CATEGORIES;
        }
        LinkedHashSet<NearbyPlaceCategory> requested = new LinkedHashSet<>(requestedCategories);
        return SUPPORTED_CATEGORIES.stream().filter(requested::contains).toList();
    }

    private NearbyPlacePoint requiredPoint(NearbyPlaceCenter center) {
        if (center.lat() == null
                || center.lng() == null
                || !Double.isFinite(center.lat())
                || !Double.isFinite(center.lng())
                || center.lat() < -90
                || center.lat() > 90
                || center.lng() < -180
                || center.lng() > 180) {
            throw new NearbyPlaceCenterUnavailableException("complex display coordinate unavailable");
        }
        return new NearbyPlacePoint(center.lat(), center.lng());
    }
}
