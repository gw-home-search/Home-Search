import { useEffect, useRef, useState, type RefObject } from 'react';

import type { MapDisplayMode } from '../../../app/mapAppTypes';
import { CheckIcon, MapToolsIcon, MinusIcon, PlusIcon } from '../../../shared/icons';
import {
  MAP_NEARBY_PLACE_CATEGORIES,
  NEARBY_PLACE_CATEGORY_LABELS,
  type NearbyPlaceCategory,
} from '../../nearby-places/api/fetchNearbyPlaces';
import { NearbyPlaceCategoryIcon } from '../../nearby-places/NearbyPlaceCategoryIcon';
import { MAX_MAP_LEVEL, MIN_MAP_LEVEL } from '../markerViewModel';
import type { MapToolMode, OpenMapControl } from '../tools/mapToolTypes';

const DISPLAY_OPTIONS: ReadonlyArray<{ ariaLabel: string; label: string; value: MapDisplayMode }> = [
  { ariaLabel: '일반 지도', label: '지도', value: 'roadmap' },
  { ariaLabel: '지형 지도', label: '지형', value: 'terrain' },
  { ariaLabel: '위성 지도', label: '위성', value: 'hybrid' },
];

const FACILITY_PICKER_LABELS: Readonly<Record<NearbyPlaceCategory, string>> = {
  ...NEARBY_PLACE_CATEGORY_LABELS,
  DAYCARE_KINDERGARTEN: '어린이집',
  SUBWAY_STATION: '지하철',
};

type MapControlRailProps = {
  activeTool: MapToolMode;
  cadastralEnabled: boolean;
  facilityCategories?: readonly NearbyPlaceCategory[];
  facilityLoading?: boolean;
  disabled: boolean;
  displayMode: MapDisplayMode;
  level: number;
  facilityToggleRef?: RefObject<HTMLButtonElement | null>;
  toolToggleRef?: RefObject<HTMLButtonElement | null>;
  onCadastralChange: (enabled: boolean) => void;
  onFacilityCategoriesChange?: (categories: NearbyPlaceCategory[]) => void;
  onDisplayModeChange: (mode: MapDisplayMode) => void;
  onToolModeChange: (mode: MapToolMode) => void;
  onZoomIn: () => void;
  onZoomOut: () => void;
};

export function MapControlRail({
  activeTool,
  cadastralEnabled,
  facilityCategories = [],
  facilityLoading = false,
  disabled,
  displayMode,
  level,
  facilityToggleRef,
  toolToggleRef,
  onCadastralChange,
  onFacilityCategoriesChange = () => undefined,
  onDisplayModeChange,
  onToolModeChange,
  onZoomIn,
  onZoomOut,
}: MapControlRailProps) {
  const [openControl, setOpenControl] = useState<OpenMapControl>(null);
  const [facilitySelectionMessage, setFacilitySelectionMessage] = useState('');
  const railRef = useRef<HTMLDivElement>(null);
  const displayToggleRef = useRef<HTMLButtonElement>(null);
  const internalToolToggleRef = useRef<HTMLButtonElement>(null);
  const internalFacilityToggleRef = useRef<HTMLButtonElement>(null);
  const resolvedToolToggleRef = toolToggleRef ?? internalToolToggleRef;
  const resolvedFacilityToggleRef = facilityToggleRef ?? internalFacilityToggleRef;
  const activeDisplay = DISPLAY_OPTIONS.find((option) => option.value === displayMode) ?? DISPLAY_OPTIONS[0];

  useEffect(() => {
    if (!openControl && activeTool === 'none') {
      return undefined;
    }

    function closeFromOutside(event: PointerEvent) {
      if (event.target instanceof Node && !railRef.current?.contains(event.target)) {
        const target = openControl === 'display'
          ? displayToggleRef.current
          : openControl === 'facilities'
            ? resolvedFacilityToggleRef.current
            : resolvedToolToggleRef.current;
        setOpenControl(null);
        queueMicrotask(() => target?.focus());
      }
    }

    function closeFromEscape(event: KeyboardEvent) {
      if (event.key !== 'Escape') {
        return;
      }
      const target = openControl === 'display'
        ? displayToggleRef.current
        : openControl === 'tools'
          ? resolvedToolToggleRef.current
          : openControl === 'facilities'
            ? resolvedFacilityToggleRef.current
            : resolvedToolToggleRef.current;
      if (openControl) setOpenControl(null);
      else onToolModeChange('none');
      queueMicrotask(() => target?.focus());
    }

    document.addEventListener('pointerdown', closeFromOutside);
    document.addEventListener('keydown', closeFromEscape);
    return () => {
      document.removeEventListener('pointerdown', closeFromOutside);
      document.removeEventListener('keydown', closeFromEscape);
    };
  }, [activeTool, onToolModeChange, openControl, resolvedFacilityToggleRef, resolvedToolToggleRef]);

  function selectDisplay(mode: MapDisplayMode) {
    onDisplayModeChange(mode);
    setOpenControl(null);
    queueMicrotask(() => displayToggleRef.current?.focus());
  }

  function selectTool(mode: Exclude<MapToolMode, 'none'>) {
    onToolModeChange(activeTool === mode ? 'none' : mode);
    setOpenControl(null);
    queueMicrotask(() => resolvedToolToggleRef.current?.focus());
  }

  function toggleCadastral() {
    onCadastralChange(!cadastralEnabled);
    setOpenControl(null);
    queueMicrotask(() => resolvedToolToggleRef.current?.focus());
  }

  function toggleFacilityCategory(category: NearbyPlaceCategory) {
    const selected = facilityCategories.includes(category);
    if (!selected && facilityCategories.length >= 3) {
      setFacilitySelectionMessage('주변시설은 최대 3개까지 선택할 수 있습니다.');
      return;
    }
    const nextSelection = new Set(facilityCategories);
    if (selected) nextSelection.delete(category);
    else nextSelection.add(category);
    setFacilitySelectionMessage('');
    onFacilityCategoriesChange(MAP_NEARBY_PLACE_CATEGORIES.filter((item) => nextSelection.has(item)));
  }

  return (
    <div className="map-control-rail" data-ui-layer="map-control-rail" ref={railRef}>
      <div aria-label="지도 조작" className="map-control-zoom-group">
        <button type="button" aria-label="지도 확대" disabled={level <= MIN_MAP_LEVEL} onClick={onZoomIn}>
          <PlusIcon aria-hidden="true" />
        </button>
        <button type="button" aria-label="지도 축소" disabled={level >= MAX_MAP_LEVEL} onClick={onZoomOut}>
          <MinusIcon aria-hidden="true" />
        </button>
      </div>

      <div className="map-control-mode-group">
        <div className="map-control-anchor">
          <button
            type="button"
            aria-controls="map-display-menu"
            aria-expanded={openControl === 'display'}
            aria-haspopup="true"
            aria-label="지도 형식 선택"
            className="map-control-toggle map-display-toggle"
            disabled={disabled}
            ref={displayToggleRef}
            onClick={() => setOpenControl(openControl === 'display' ? null : 'display')}
          >
            {activeDisplay.label}
          </button>
          {openControl === 'display' ? (
            <div aria-label="지도 형식 메뉴" className="map-control-menu" id="map-display-menu" role="group">
              {DISPLAY_OPTIONS.map((option) => (
                <button
                  type="button"
                  aria-label={option.ariaLabel}
                  aria-pressed={displayMode === option.value}
                  key={option.value}
                  onClick={() => selectDisplay(option.value)}
                >
                  {option.label}
                </button>
              ))}
            </div>
          ) : null}
        </div>

        <div className="map-control-anchor">
          <button
            type="button"
            aria-controls="map-tools-menu"
            aria-expanded={openControl === 'tools'}
            aria-haspopup="true"
            aria-label="지도 도구 선택"
            className="map-control-toggle map-tool-toggle"
            data-active={activeTool === 'roadview' || activeTool === 'distance' || cadastralEnabled}
            disabled={disabled}
            ref={resolvedToolToggleRef}
            onClick={() => setOpenControl(openControl === 'tools' ? null : 'tools')}
          >
            <MapToolsIcon aria-hidden="true" />
          </button>
          {openControl === 'tools' ? (
            <div aria-label="지도 도구 메뉴" className="map-control-menu" id="map-tools-menu" role="group">
              <button type="button" aria-label="거리뷰 사용" aria-pressed={activeTool === 'roadview'} onClick={() => selectTool('roadview')}>거리뷰</button>
              <button type="button" aria-label="지적편집도 표시" aria-pressed={cadastralEnabled} onClick={toggleCadastral}>지적</button>
              <button type="button" aria-label="거리 측정 사용" aria-pressed={activeTool === 'distance'} onClick={() => selectTool('distance')}>거리</button>
            </div>
          ) : null}
        </div>

        <div className="map-control-anchor map-facility-anchor">
          {openControl === 'facilities' ? (
            <div aria-label="주변시설 종류 선택" className="map-facility-picker" id="map-facility-picker" role="group">
              <div className="map-facility-picker-header">
                <div>
                  <strong>주변시설</strong>
                  <span>최대 3개</span>
                </div>
                <button
                  type="button"
                  aria-label="주변시설 전체 해제"
                  disabled={facilityCategories.length === 0}
                  onClick={() => {
                    setFacilitySelectionMessage('');
                    onFacilityCategoriesChange([]);
                  }}
                >
                  전체 해제
                </button>
              </div>
              <div className="map-facility-options">
                {MAP_NEARBY_PLACE_CATEGORIES.map((category) => (
                  <button
                    type="button"
                    aria-label={`${NEARBY_PLACE_CATEGORY_LABELS[category]} ${facilityCategories.includes(category) ? '선택 해제' : '선택'}`}
                    aria-pressed={facilityCategories.includes(category)}
                    key={category}
                    onClick={() => toggleFacilityCategory(category)}
                  >
                    <NearbyPlaceCategoryIcon category={category} />
                    <span className="map-facility-category-label">{FACILITY_PICKER_LABELS[category]}</span>
                    {facilityCategories.includes(category) ? (
                      <CheckIcon aria-hidden="true" className="map-facility-category-check" />
                    ) : <span aria-hidden="true" className="map-facility-category-placeholder" />}
                  </button>
                ))}
              </div>
              {facilitySelectionMessage ? (
                <span className="map-facility-live" aria-live="polite">{facilitySelectionMessage}</span>
              ) : null}
            </div>
          ) : null}
          <button
            type="button"
            aria-controls="map-facility-picker"
            aria-expanded={openControl === 'facilities'}
            aria-haspopup="true"
            aria-label="주변시설 선택"
            className="map-control-toggle map-facility-toggle"
            data-active={facilityCategories.length > 0}
            data-loading={facilityLoading}
            disabled={disabled}
            ref={resolvedFacilityToggleRef}
            title={`주변시설 선택 · ${facilityCategories.length}개 선택`}
            onClick={() => setOpenControl(openControl === 'facilities' ? null : 'facilities')}
          >
            시설
            {facilityCategories.length > 0 ? <span className="map-facility-count">{facilityCategories.length}</span> : null}
          </button>
        </div>
      </div>
    </div>
  );
}
