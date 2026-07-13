import { useCallback, useState } from 'react';

import type {
  DistanceMeasureState,
  MapPoint,
  MapToolMode,
  RoadviewRuntimeState,
} from './mapToolTypes';

const EMPTY_DISTANCE: DistanceMeasureState = {
  phase: 'idle',
  points: [],
  lengthMeters: 0,
};

export function useMapToolState() {
  const [activeTool, setActiveTool] = useState<MapToolMode>('none');
  const [cadastralEnabled, setCadastralEnabled] = useState(false);
  const [distance, setDistance] = useState<DistanceMeasureState>(EMPTY_DISTANCE);
  const [roadviewState, setRoadviewState] = useState<RoadviewRuntimeState>('idle');

  const changeTool = useCallback((nextTool: MapToolMode) => {
    setActiveTool(nextTool);
    setDistance(nextTool === 'distance' ? { ...EMPTY_DISTANCE, phase: 'drawing' } : EMPTY_DISTANCE);
    setRoadviewState(nextTool === 'roadview' ? 'loading' : 'idle');
  }, []);

  const addDistancePoint = useCallback((point: MapPoint) => {
    setDistance((current) => current.phase === 'drawing'
      ? { ...current, points: [...current.points, point] }
      : current);
  }, []);

  const setDistanceLength = useCallback((lengthMeters: number) => {
    setDistance((current) => ({ ...current, lengthMeters }));
  }, []);

  const undoDistancePoint = useCallback(() => {
    setDistance((current) => ({
      ...current,
      points: current.points.slice(0, -1),
      lengthMeters: 0,
    }));
  }, []);

  const resetDistance = useCallback(() => {
    setDistance({ ...EMPTY_DISTANCE, phase: 'drawing' });
  }, []);

  const completeDistance = useCallback(() => {
    setDistance((current) => current.points.length >= 2
      ? { ...current, phase: 'complete' }
      : current);
  }, []);

  const exitActiveTool = useCallback(() => {
    setActiveTool('none');
    setDistance(EMPTY_DISTANCE);
    setRoadviewState('idle');
  }, []);

  return {
    activeTool,
    cadastralEnabled,
    distance,
    roadviewState,
    addDistancePoint,
    changeTool,
    completeDistance,
    exitActiveTool,
    resetDistance,
    setCadastralEnabled,
    setDistanceLength,
    setRoadviewState,
    undoDistancePoint,
  };
}
