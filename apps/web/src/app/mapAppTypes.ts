import type { MapMarkersResult } from '../features/map/api/fetchMapMarkers';

export type MarkerRequestState = 'loading' | 'ready' | 'empty' | 'error';
export type DetailRequestState = 'idle' | 'loading' | 'ready' | 'error';
export type PanelRequestState = 'idle' | 'loading' | 'ready' | 'empty' | 'error';
export type SidebarMode = 'region' | 'search' | 'detail';
export type MapDisplayMode = 'roadmap' | 'terrain' | 'hybrid';

export type MapViewport = {
  bounds: {
    swLat: number;
    swLng: number;
    neLat: number;
    neLng: number;
  };
  level: number;
};

export type MapFocusTarget = {
  lat: number;
  lng: number;
  level: number;
  seq: number;
};

export type MapUiCommand = {
  type: 'showNearbyCategory';
  actionId: string;
  category: 'HOSPITAL' | 'DAYCARE_KINDERGARTEN';
};

export type ComplexSelection = {
  parcelId: number | null;
  complexId: number | null;
};

export type RegionTrailItem = {
  id: number;
  name: string;
};

export type ComplexMapMarker = Extract<MapMarkersResult, { kind: 'complex' }>['markers'][number];
export type RegionMapMarker = Extract<MapMarkersResult, { kind: 'region' }>['markers'][number];
