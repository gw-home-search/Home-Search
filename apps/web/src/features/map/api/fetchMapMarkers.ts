import {
  fetchComplexMarkers,
  type ComplexMarker,
  type ComplexMarkersRequest,
} from './fetchComplexMarkers';
import {
  fetchRegionMarkers,
  type RegionLevel,
  type RegionMarker,
  type RegionMarkersRequest,
} from './fetchRegionMarkers';

export type MapBoundsRequest = Pick<ComplexMarkersRequest, 'swLat' | 'swLng' | 'neLat' | 'neLng'>;
export type ComplexMarkerFilters = Omit<
  ComplexMarkersRequest,
  'swLat' | 'swLng' | 'neLat' | 'neLng'
>;

export type MapMarkersRequest = {
  bounds: MapBoundsRequest;
  filters?: ComplexMarkerFilters;
  level: number;
};

export type MapMarkersResult =
  | {
      kind: 'complex';
      markers: ComplexMarker[];
    }
  | {
      kind: 'region';
      level: RegionLevel;
      markers: RegionMarker[];
    };

export const MAX_COMPLEX_MARKER_LEVEL = 4;

export async function fetchMapMarkers(
  request: MapMarkersRequest,
  signal?: AbortSignal,
): Promise<MapMarkersResult> {
  if (request.level <= MAX_COMPLEX_MARKER_LEVEL) {
    const markers = await fetchComplexMarkers(
      {
        ...request.bounds,
        ...EMPTY_COMPLEX_MARKER_FILTERS,
        ...request.filters,
      },
      signal,
    );

    return { kind: 'complex', markers };
  }

  const region = regionLevelForMapLevel(request.level);
  const markers = await fetchRegionMarkers(
    {
      ...request.bounds,
      region,
    } satisfies RegionMarkersRequest,
    signal,
  );

  return { kind: 'region', level: region, markers };
}

const EMPTY_COMPLEX_MARKER_FILTERS: Required<ComplexMarkerFilters> = {
  pyeongMin: null,
  pyeongMax: null,
  priceEokMin: null,
  priceEokMax: null,
  ageMin: null,
  ageMax: null,
  unitMin: null,
  unitMax: null,
};

export function regionLevelForMapLevel(level: number): RegionLevel {
  if (level >= 10) {
    return 'si-do';
  }

  if (level >= 7) {
    return 'si-gun-gu';
  }

  return 'eup-myeon-dong';
}
