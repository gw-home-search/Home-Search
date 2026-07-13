export type MapToolMode = 'none' | 'roadview' | 'distance' | 'commerce';
export type OpenMapControl = null | 'display' | 'tools';
export type DistanceMeasurePhase = 'idle' | 'drawing' | 'complete';
export type RoadviewRuntimeState = 'idle' | 'loading' | 'ready' | 'unavailable' | 'error';

export type MapPoint = {
  lat: number;
  lng: number;
};

export type DistanceMeasureState = {
  phase: DistanceMeasurePhase;
  points: MapPoint[];
  lengthMeters: number;
};
