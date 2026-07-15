import type { ViewportNearbyPlaces } from './api/fetchViewportNearbyPlaces';
import type { NearbyPlace, NearbyPlaceCategory } from './api/fetchNearbyPlaces';

export type MapNearbyPlace = {
  category: NearbyPlaceCategory;
  place: NearbyPlace;
};

const MAX_PER_CATEGORY = 5;
const MAX_TOTAL = 15;

export function createMapNearbyPlaces(
  results: readonly ViewportNearbyPlaces[],
  selectedCategories: readonly NearbyPlaceCategory[],
): MapNearbyPlace[] {
  const resultByCategory = new Map(results.map((result) => [result.category.category, result]));
  const seenPlaceIds = new Set<string>();
  const places: MapNearbyPlace[] = [];

  for (const category of selectedCategories) {
    const categoryPlaces = resultByCategory.get(category)?.category.places ?? [];
    let categoryCount = 0;
    for (const place of categoryPlaces) {
      if (seenPlaceIds.has(place.placeId)) continue;
      seenPlaceIds.add(place.placeId);
      places.push({ category, place });
      categoryCount += 1;
      if (places.length === MAX_TOTAL) return places;
      if (categoryCount === MAX_PER_CATEGORY) break;
    }
  }
  return places;
}
