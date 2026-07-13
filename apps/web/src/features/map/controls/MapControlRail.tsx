import { useEffect, useRef, useState, type RefObject } from 'react';

import type { MapDisplayMode } from '../../../app/mapAppTypes';
import { MapToolsIcon, MinusIcon, PlusIcon } from '../../../shared/icons';
import { MAX_MAP_LEVEL, MIN_MAP_LEVEL } from '../markerViewModel';
import type { MapToolMode, OpenMapControl } from '../tools/mapToolTypes';

const DISPLAY_OPTIONS: ReadonlyArray<{ ariaLabel: string; label: string; value: MapDisplayMode }> = [
  { ariaLabel: '일반 지도', label: '지도', value: 'roadmap' },
  { ariaLabel: '지형 지도', label: '지형', value: 'terrain' },
  { ariaLabel: '위성 지도', label: '위성', value: 'hybrid' },
];

type MapControlRailProps = {
  activeTool: MapToolMode;
  cadastralEnabled: boolean;
  commerceAvailable: boolean;
  disabled: boolean;
  displayMode: MapDisplayMode;
  level: number;
  commerceToggleRef?: RefObject<HTMLButtonElement | null>;
  toolToggleRef?: RefObject<HTMLButtonElement | null>;
  onCadastralChange: (enabled: boolean) => void;
  onDisplayModeChange: (mode: MapDisplayMode) => void;
  onToolModeChange: (mode: MapToolMode) => void;
  onZoomIn: () => void;
  onZoomOut: () => void;
};

export function MapControlRail({
  activeTool,
  cadastralEnabled,
  commerceAvailable,
  disabled,
  displayMode,
  level,
  commerceToggleRef,
  toolToggleRef,
  onCadastralChange,
  onDisplayModeChange,
  onToolModeChange,
  onZoomIn,
  onZoomOut,
}: MapControlRailProps) {
  const [openControl, setOpenControl] = useState<OpenMapControl>(null);
  const railRef = useRef<HTMLDivElement>(null);
  const displayToggleRef = useRef<HTMLButtonElement>(null);
  const internalToolToggleRef = useRef<HTMLButtonElement>(null);
  const internalCommerceToggleRef = useRef<HTMLButtonElement>(null);
  const resolvedToolToggleRef = toolToggleRef ?? internalToolToggleRef;
  const resolvedCommerceToggleRef = commerceToggleRef ?? internalCommerceToggleRef;
  const activeDisplay = DISPLAY_OPTIONS.find((option) => option.value === displayMode) ?? DISPLAY_OPTIONS[0];

  useEffect(() => {
    if (!openControl && activeTool === 'none') {
      return undefined;
    }

    function closeFromOutside(event: PointerEvent) {
      if (event.target instanceof Node && !railRef.current?.contains(event.target)) {
        setOpenControl(null);
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
          : activeTool === 'commerce'
            ? resolvedCommerceToggleRef.current
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
  }, [activeTool, onToolModeChange, openControl, resolvedCommerceToggleRef, resolvedToolToggleRef]);

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

  function toggleCommerce() {
    onToolModeChange(activeTool === 'commerce' ? 'none' : 'commerce');
    setOpenControl(null);
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

        <button
          type="button"
          aria-label="주변 상권 보기"
          aria-pressed={activeTool === 'commerce'}
          className="map-control-toggle map-commerce-toggle"
          data-active={activeTool === 'commerce'}
          disabled={disabled || !commerceAvailable}
          ref={resolvedCommerceToggleRef}
          title={commerceAvailable ? '주변 상권·생활시설 보기' : '단지를 선택하면 주변 상권을 볼 수 있습니다.'}
          onClick={toggleCommerce}
        >
          상권
        </button>
      </div>
    </div>
  );
}
