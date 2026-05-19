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

export type MapMarkersRequest = {
  bounds: MapBoundsRequest;
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

export async function fetchMapMarkers(request: MapMarkersRequest): Promise<MapMarkersResult> {
  if (request.level <= 4) {
    const markers = await fetchComplexMarkers({
      ...request.bounds,
      pyeongMin: null,
      pyeongMax: null,
      priceEokMin: null,
      priceEokMax: null,
      ageMin: null,
      ageMax: null,
      unitMin: null,
      unitMax: null,
    });

    return { kind: 'complex', markers };
  }

  const region = regionLevelForMapLevel(request.level);
  const markers = await fetchRegionMarkers({
    ...request.bounds,
    region,
  } satisfies RegionMarkersRequest);

  return { kind: 'region', level: region, markers };
}

export function regionLevelForMapLevel(level: number): RegionLevel {
  if (level >= 10) {
    return 'si-do';
  }

  if (level >= 7) {
    return 'si-gun-gu';
  }

  return 'eup-myeon-dong';
}
