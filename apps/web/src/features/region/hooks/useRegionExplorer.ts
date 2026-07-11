import { useEffect, useRef, useState } from 'react';

import type { PanelRequestState, RegionTrailItem } from '../../../app/mapAppTypes';
import type { RegionComplexSummary } from '../api/fetchRegions';
import {
  fetchRegionComplexes,
  fetchRegionDetail,
  fetchRootRegions,
  type RegionDetail,
  type RegionSummary,
} from '../api/fetchRegions';
import { SEARCH_FOCUS_DELTA } from '../../search/hooks/useComplexSearch';

export function useRegionExplorer({
  focusMap,
  initialRegionLoad,
  onComplexSelect,
}: {
  focusMap: (lat: number, lng: number, level: number, delta: number) => void;
  initialRegionLoad: boolean;
  onComplexSelect: (complex: RegionComplexSummary) => void;
}) {
  const [rootRegions, setRootRegions] = useState<RegionSummary[]>([]);
  const [regionDetail, setRegionDetail] = useState<RegionDetail | null>(null);
  const [regionComplexes, setRegionComplexes] = useState<RegionComplexSummary[]>([]);
  const [regionState, setRegionState] = useState<PanelRequestState>('idle');
  const [regionError, setRegionError] = useState<string | null>(null);
  const [regionTrail, setRegionTrail] = useState<RegionTrailItem[]>([]);
  const regionRequestSeq = useRef(0);
  const initialRegionLoadStarted = useRef(false);
  const lastRegionSelection = useRef<{
    region: RegionTrailItem;
    parentTrail: RegionTrailItem[];
  } | null>(null);
  const regionPending = useRef(false);

  useEffect(() => {
    if (!initialRegionLoad || initialRegionLoadStarted.current) {
      return;
    }
    initialRegionLoadStarted.current = true;
    loadRootRegions();
  }, [initialRegionLoad]);

  function loadRootRegions() {
    if (regionPending.current) return;
    lastRegionSelection.current = null;
    regionPending.current = true;
    const requestSeq = regionRequestSeq.current + 1;
    regionRequestSeq.current = requestSeq;
    setRegionState('loading');
    setRegionError(null);
    setRegionDetail(null);
    setRegionComplexes([]);
    setRegionTrail([]);

    fetchRootRegions()
      .then((nextRegions) => {
        if (requestSeq !== regionRequestSeq.current) {
          return;
        }
        setRootRegions(nextRegions);
        setRegionComplexes([]);
        setRegionState(nextRegions.length === 0 ? 'empty' : 'ready');
        regionPending.current = false;
      })
      .catch((error: unknown) => {
        if (requestSeq !== regionRequestSeq.current) {
          return;
        }
        setRootRegions([]);
        setRegionDetail(null);
        setRegionComplexes([]);
        setRegionTrail([]);
        setRegionState('error');
        setRegionError(error instanceof Error ? error.message : '알 수 없는 지역 오류');
        regionPending.current = false;
      });
  }

  function handleRegionSelect(region: RegionTrailItem) {
    loadRegion(region, regionTrail);
  }

  function handleMapRegionSelect(region: RegionTrailItem, currentMapLevel: number) {
    loadRegion(region, regionTrail, nextRegionMapLevel(currentMapLevel));
  }

  function handleRegionTrailSelect(region: RegionTrailItem, index: number) {
    loadRegion(region, regionTrail.slice(0, index));
  }

  function loadRegion(region: RegionTrailItem, parentTrail: RegionTrailItem[], mapLevelOverride?: number) {
    if (regionPending.current) return;
    lastRegionSelection.current = { region, parentTrail };
    regionPending.current = true;
    const requestSeq = regionRequestSeq.current + 1;
    regionRequestSeq.current = requestSeq;
    const nextTrail = [...parentTrail, region];
    const nextMapLevel = mapLevelOverride ?? regionFocusLevel(nextTrail.length);
    setRegionState('loading');
    setRegionError(null);
    setRootRegions([]);
    setRegionComplexes([]);

    fetchRegionDetail(region.id)
      .then(async (nextDetail) => {
        const nextComplexes = nextDetail.children.length === 0
          ? await fetchRegionComplexes(region.id, { limit: 20, offset: 0 })
          : [];
        return { nextDetail, nextComplexes };
      })
      .then(({ nextDetail, nextComplexes }) => {
        if (requestSeq !== regionRequestSeq.current) {
          return;
        }
        setRegionDetail(nextDetail);
        setRegionComplexes(nextComplexes);
        setRootRegions(nextDetail.children);
        setRegionTrail([...parentTrail, { id: nextDetail.id, name: nextDetail.name }]);
        setRegionState('ready');
        regionPending.current = false;
        focusMap(
          nextDetail.latitude,
          nextDetail.longitude,
          nextMapLevel,
          mapFocusDeltaForLevel(nextMapLevel),
        );
      })
      .catch((error: unknown) => {
        if (requestSeq !== regionRequestSeq.current) {
          return;
        }
        setRegionDetail(null);
        setRegionComplexes([]);
        setRegionState('error');
        setRegionError(error instanceof Error ? error.message : '알 수 없는 지역 상세 오류');
        regionPending.current = false;
      });
  }

  function handleRegionComplexSelect(complex: RegionComplexSummary) {
    onComplexSelect(complex);
    if (hasDisplayCoordinate(complex)) {
      focusMap(complex.latitude, complex.longitude, 4, SEARCH_FOCUS_DELTA);
    }
  }

  return {
    handleRegionComplexSelect,
    handleMapRegionSelect,
    handleRegionSelect,
    handleRegionTrailSelect,
    loadRootRegions,
    regionComplexes,
    regionDetail,
    regionError,
    regionState,
    regionTrail,
    retryRegion: () => {
      if (regionPending.current) return;
      const selection = lastRegionSelection.current;
      if (selection) loadRegion(selection.region, selection.parentTrail);
      else loadRootRegions();
    },
    rootRegions,
  };
}

function nextRegionMapLevel(currentLevel: number): number {
  if (currentLevel >= 10) return 9;
  if (currentLevel >= 7) return 6;
  return 4;
}

export function mapFocusDeltaForLevel(level: number): number {
  if (level >= 8) {
    return 0.2;
  }
  if (level >= 6) {
    return 0.08;
  }
  return SEARCH_FOCUS_DELTA;
}

function regionFocusLevel(depth: number): number {
  if (depth <= 1) {
    return 9;
  }
  if (depth === 2) {
    return 6;
  }
  return 4;
}

function hasDisplayCoordinate(
  complex: RegionComplexSummary,
): complex is RegionComplexSummary & { latitude: number; longitude: number } {
  return complex.latitude != null && complex.longitude != null;
}
