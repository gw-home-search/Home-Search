import {
  useEffect,
  useRef,
  useState,
  type CSSProperties,
  type FormEvent,
  type Ref,
} from 'react';

import { RefreshIcon, SearchIcon } from '../../shared/icons';
import type { ComplexMarkerFilters } from '../map/api/fetchMapMarkers';

type FilterKey = 'unit' | 'pyeong' | 'price' | 'age';
type CompleteFilters = Required<ComplexMarkerFilters>;

type FilterPanelProps = {
  activeFilterCount: number;
  filters: CompleteFilters;
  explorationButtonRef?: Ref<HTMLButtonElement>;
  onChange: (filters: CompleteFilters) => void;
  onOpenExploration?: () => void;
  onReset: () => void;
};

type FilterDefinition = {
  key: FilterKey;
  label: string;
  minName: keyof CompleteFilters;
  maxName: keyof CompleteFilters;
  minLabel: string;
  maxLabel: string;
  unit: string;
  ceiling: number;
  step: number;
};

const FILTERS: FilterDefinition[] = [
  { key: 'unit', label: '세대수', minName: 'unitMin', maxName: 'unitMax', minLabel: '최소 세대수', maxLabel: '최대 세대수', unit: '세대', ceiling: 5000, step: 1 },
  { key: 'pyeong', label: '평형', minName: 'pyeongMin', maxName: 'pyeongMax', minLabel: '최소 평형', maxLabel: '최대 평형', unit: '평', ceiling: 120, step: 1 },
  { key: 'price', label: '가격', minName: 'priceEokMin', maxName: 'priceEokMax', minLabel: '최소 가격 억', maxLabel: '최대 가격 억', unit: '억', ceiling: 80, step: 0.1 },
  { key: 'age', label: '입주년차', minName: 'ageMin', maxName: 'ageMax', minLabel: '최소 연식', maxLabel: '최대 연식', unit: '년', ceiling: 40, step: 1 },
];

export function FilterPanel({
  activeFilterCount,
  filters,
  explorationButtonRef,
  onChange,
  onOpenExploration,
  onReset,
}: FilterPanelProps) {
  const [openFilter, setOpenFilter] = useState<FilterKey | null>(null);
  const [draftMin, setDraftMin] = useState('');
  const [draftMax, setDraftMax] = useState('');
  const [validationError, setValidationError] = useState<string | null>(null);
  const [popoverLeft, setPopoverLeft] = useState(8);
  const draftMinRef = useRef('');
  const draftMaxRef = useRef('');
  const rootRef = useRef<HTMLFormElement>(null);
  const chipRefs = useRef<Partial<Record<FilterKey, HTMLButtonElement | null>>>({});

  useEffect(() => {
    function discardDraft(event: PointerEvent) {
      if (rootRef.current?.contains(event.target as Node)) return;
      closePopover(true);
    }
    function closeWithEscape(event: KeyboardEvent) {
      if (event.key !== 'Escape' || openFilter == null) return;
      closePopover(true);
    }
    document.addEventListener('pointerdown', discardDraft);
    document.addEventListener('keydown', closeWithEscape);
    return () => {
      document.removeEventListener('pointerdown', discardDraft);
      document.removeEventListener('keydown', closeWithEscape);
    };
  }, [openFilter]);

  const definition = FILTERS.find((filter) => filter.key === openFilter) ?? null;
  const effectiveCeiling = definition == null
    ? 0
    : Math.max(definition.ceiling, finiteDraft(draftMin) ?? 0, finiteDraft(draftMax) ?? 0);

  function openPopover(filter: FilterDefinition) {
    if (openFilter === filter.key) {
      closePopover(false);
      return;
    }
    updateDraftMin(formValue(filters[filter.minName]));
    updateDraftMax(formValue(filters[filter.maxName]));
    setValidationError(null);
    setOpenFilter(filter.key);
    const chipRect = chipRefs.current[filter.key]?.getBoundingClientRect();
    const formRect = rootRef.current?.getBoundingClientRect();
    if (chipRect && formRect) {
      setPopoverLeft(Math.max(8, Math.min(chipRect.left - formRect.left, formRect.width - 328)));
    }
  }

  function closePopover(restoreFocus: boolean) {
    const chip = openFilter == null ? null : chipRefs.current[openFilter];
    setOpenFilter(null);
    setValidationError(null);
    if (restoreFocus) chip?.focus();
  }

  function applyDraft(event?: FormEvent<HTMLFormElement>) {
    event?.preventDefault();
    if (!definition) return;
    const parsed = parseAndValidateRange(draftMinRef.current, draftMaxRef.current, definition.step);
    if ('error' in parsed) {
      setValidationError(parsed.error);
      return;
    }
    onChange({
      ...filters,
      [definition.minName]: parsed.min,
      [definition.maxName]: parsed.max,
    });
    closePopover(true);
  }

  function resetGroup(filter: FilterDefinition) {
    onChange({ ...filters, [filter.minName]: null, [filter.maxName]: null });
    closePopover(true);
  }

  function updateDraftMin(value: string) {
    draftMinRef.current = value;
    setDraftMin(value);
  }

  function updateDraftMax(value: string) {
    draftMaxRef.current = value;
    setDraftMax(value);
  }

  return (
    <form
      ref={rootRef}
      aria-label="마커 필터"
      className="filter-panel"
      noValidate
      data-filter-state={activeFilterCount > 0 ? 'active' : 'idle'}
      data-map-overlay="filters"
      data-ui-layer="filter-controls"
      onSubmit={applyDraft}
    >
      <div className="filter-chip-scroller">
        <div className="filter-chip-list" aria-label="단지 필터 목록">
          <button
            ref={explorationButtonRef}
            type="button"
            aria-controls="exploration-panel"
            aria-label="검색 패널 열기"
            className="mobile-search-action"
            onClick={onOpenExploration}
          >
            <SearchIcon aria-hidden="true" />
            검색
          </button>
          {FILTERS.map((filter) => {
            const isOpen = openFilter === filter.key;
            const isApplied = hasRange(filters, filter);
            return (
              <button
                ref={(element) => { chipRefs.current[filter.key] = element; }}
                key={filter.key}
                type="button"
                aria-controls={`filter-popover-${filter.key}`}
                aria-expanded={isOpen}
                aria-label={`${filter.label} 필터 ${isOpen ? '닫기' : '열기'}`}
                className="filter-chip"
                data-active={isApplied ? 'true' : 'false'}
                data-open={isOpen ? 'true' : 'false'}
                onClick={() => openPopover(filter)}
              >
                <span className="filter-chip-copy">
                  <span>{filter.label}</span>
                  {isApplied ? <span>{formatFilterRange(filters, filter)}</span> : null}
                </span>
              </button>
            );
          })}
          <button
            type="button"
            className="filter-reset"
            aria-label="마커 필터 초기화"
            title="필터 전체 초기화"
            disabled={activeFilterCount === 0}
            onClick={() => {
              closePopover(false);
              onReset();
            }}
          >
            <RefreshIcon aria-hidden="true" />
            <span className="filter-reset-label">초기화</span>
          </button>
        </div>
      </div>

      {definition ? (
        <fieldset
          key={definition.key}
          id={`filter-popover-${definition.key}`}
          className="filter-popover"
          style={{ left: popoverLeft } as CSSProperties}
        >
          <legend>{definition.label} 범위</legend>
          <div className="filter-slider" style={{ '--filter-min': sliderPercent(draftMin, effectiveCeiling, 0), '--filter-max': sliderPercent(draftMax, effectiveCeiling, 100) } as CSSProperties}>
            <div className="filter-slider-track" aria-hidden="true"><span /></div>
            <input
              aria-label={`최소 ${definition.label} 슬라이더`}
              min="0"
              max={effectiveCeiling}
              step={definition.step}
              type="range"
              value={finiteDraft(draftMin) ?? 0}
              onInput={(event) => { updateDraftMin(event.currentTarget.value); setValidationError(null); }}
            />
            <input
              aria-label={`최대 ${definition.label} 슬라이더`}
              min="0"
              max={effectiveCeiling}
              step={definition.step}
              type="range"
              value={finiteDraft(draftMax) ?? effectiveCeiling}
              onInput={(event) => { updateDraftMax(event.currentTarget.value); setValidationError(null); }}
            />
          </div>
          <div className="filter-range">
            <label className="filter-range-field">
              <span className="filter-range-label">최소</span>
              <span className="filter-number-field">
                <input autoFocus aria-label={definition.minLabel} aria-invalid={validationError != null} inputMode="decimal" min="0" name="filterMin" step={definition.step} type="number" value={draftMin} onInput={(event) => { updateDraftMin(event.currentTarget.value); setValidationError(null); }} />
                <span className="filter-number-field-unit">{definition.unit}</span>
              </span>
            </label>
            <span aria-hidden="true" className="filter-range-separator">–</span>
            <label className="filter-range-field">
              <span className="filter-range-label">최대</span>
              <span className="filter-number-field">
                <input aria-label={definition.maxLabel} aria-invalid={validationError != null} inputMode="decimal" min="0" name="filterMax" step={definition.step} type="number" value={draftMax} onInput={(event) => { updateDraftMax(event.currentTarget.value); setValidationError(null); }} />
                <span className="filter-number-field-unit">{definition.unit}</span>
              </span>
            </label>
          </div>
          {validationError ? <p className="filter-validation-error" role="alert">{validationError}</p> : null}
          <div className="filter-popover-actions">
            <button type="button" aria-label={`${definition.label} 필터 초기화`} onClick={() => resetGroup(definition)}>이 필터 초기화</button>
            <button type="submit" aria-label={`${definition.label} 필터 적용`}>적용</button>
          </div>
        </fieldset>
      ) : null}
    </form>
  );
}

export function countActiveFilterGroups(filters: CompleteFilters): number {
  return FILTERS.filter((filter) => hasRange(filters, filter)).length;
}

function parseAndValidateRange(minValue: string, maxValue: string, step: number): { min: number | null; max: number | null } | { error: string } {
  const min = numericDraft(minValue);
  const max = numericDraft(maxValue);
  if (min === 'invalid' || max === 'invalid') return { error: '올바른 숫자를 입력해주세요' };
  if ((min != null && min < 0) || (max != null && max < 0)) return { error: '0 이상을 입력해주세요' };
  if (step === 1 && ((min != null && !Number.isInteger(min)) || (max != null && !Number.isInteger(max)))) {
    return { error: '정수로 입력해주세요' };
  }
  if (min != null && max != null && min > max) return { error: '최소값은 최대값보다 클 수 없습니다' };
  return { min, max };
}

function numericDraft(value: string): number | null | 'invalid' {
  if (value.trim() === '') return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 'invalid';
}

function finiteDraft(value: string): number | null {
  const parsed = numericDraft(value);
  return typeof parsed === 'number' ? parsed : null;
}

function sliderPercent(value: string, ceiling: number, fallback: number): string {
  const number = finiteDraft(value);
  return `${number == null || ceiling === 0 ? fallback : Math.min(100, Math.max(0, number / ceiling * 100))}%`;
}

function hasRange(filters: CompleteFilters, filter: FilterDefinition): boolean {
  return filters[filter.minName] != null || filters[filter.maxName] != null;
}

function formatFilterRange(filters: CompleteFilters, filter: FilterDefinition): string {
  const min = filters[filter.minName];
  const max = filters[filter.maxName];
  if (min == null && max == null) return '전체';
  if (min != null && max != null) return `${formatNumber(min)}–${formatNumber(max)}${filter.unit}`;
  if (min != null) return `${formatNumber(min)}${filter.unit} 이상`;
  return `${formatNumber(max as number)}${filter.unit} 이하`;
}

function formatNumber(value: number): string {
  return value.toLocaleString('ko-KR', { maximumFractionDigits: 2 });
}

function formValue(value: number | null): string {
  return value == null ? '' : String(value);
}
