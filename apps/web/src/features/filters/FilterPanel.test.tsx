import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { ComplexMarkerFilters } from '../map/api/fetchMapMarkers';
import { FilterPanel } from './FilterPanel';

const EMPTY_FILTERS: Required<ComplexMarkerFilters> = {
  pyeongMin: null,
  pyeongMax: null,
  priceEokMin: null,
  priceEokMax: null,
  ageMin: null,
  ageMax: null,
  unitMin: null,
  unitMax: null,
};

describe('FilterPanel', () => {
  let root: Root | null = null;
  let host: HTMLDivElement | null = null;

  afterEach(() => {
    if (root) {
      act(() => root?.unmount());
      root = null;
    }
    host?.remove();
    host = null;
  });

  it('KOSA team5 기본 상태는 label-only chip 뒤에 초기화 pill을 표시한다', async () => {
    const testHost = document.createElement('div');
    host = testHost;
    document.body.append(testHost);
    root = createRoot(testHost);

    await act(async () => {
      root?.render(
        <FilterPanel
          activeFilterCount={0}
          filters={EMPTY_FILTERS}
          onChange={vi.fn()}
          onReset={vi.fn()}
        />,
      );
    });

    const unitChip = testHost.querySelector<HTMLButtonElement>('button[aria-label="세대수 필터 열기"]');
    expect(unitChip?.textContent).toBe('세대수');
    expect(unitChip?.querySelector('svg')).toBeNull();
    expect(testHost.querySelector('.filter-status')).toBeNull();
    const chipList = testHost.querySelector('.filter-chip-list');
    expect(chipList?.querySelector('button[aria-label="마커 필터 초기화"]')?.textContent).toContain('초기화');
  });

  it('평형 popover는 min/max field, 고정 unit, 중앙 separator, nowrap action 구조를 유지한다', async () => {
    const testHost = document.createElement('div');
    host = testHost;
    document.body.append(testHost);
    root = createRoot(testHost);

    await act(async () => {
      root?.render(
        <FilterPanel
          activeFilterCount={0}
          filters={EMPTY_FILTERS}
          onChange={vi.fn()}
          onReset={vi.fn()}
        />,
      );
    });

    act(() => testHost.querySelector<HTMLButtonElement>('button[aria-label="평형 필터 열기"]')?.click());

    expect(testHost.querySelectorAll('.filter-range-field')).toHaveLength(2);
    expect(Array.from(testHost.querySelectorAll('.filter-number-field-unit')).map((unit) => unit.textContent))
      .toEqual(['평', '평']);
    expect(testHost.querySelector('.filter-range-separator')?.textContent).toBe('–');
    expect(testHost.querySelector('.filter-popover-actions')?.textContent).toContain('이 필터 초기화');
  });

  it('가격 범위를 적용하고 초기화하며 Escape 후 가격 chip으로 focus를 복귀한다', async () => {
    const testHost = document.createElement('div');
    host = testHost;
    document.body.append(testHost);
    root = createRoot(testHost);
    const onChange = vi.fn();

    await act(async () => {
      root?.render(
        <FilterPanel
          activeFilterCount={0}
          filters={EMPTY_FILTERS}
          onChange={onChange}
          onReset={() => onChange(EMPTY_FILTERS)}
        />,
      );
    });

    const priceChip = testHost.querySelector<HTMLButtonElement>('button[aria-label="가격 필터 열기"]');
    expect(priceChip).not.toBeNull();

    act(() => priceChip?.click());

    const minimum = testHost.querySelector<HTMLInputElement>('input[aria-label="최소 가격 억"]');
    const maximum = testHost.querySelector<HTMLInputElement>('input[aria-label="최대 가격 억"]');
    expect(minimum).not.toBeNull();
    expect(maximum).not.toBeNull();

    act(() => {
      setInputValue(minimum, '8.5');
      setInputValue(maximum, '15');
      testHost.querySelector<HTMLButtonElement>('button[aria-label="가격 필터 적용"]')?.click();
    });

    expect(onChange).toHaveBeenLastCalledWith({
      ...EMPTY_FILTERS,
      priceEokMin: 8.5,
      priceEokMax: 15,
    });

    act(() => priceChip?.click());
    act(() => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    });

    expect(priceChip?.getAttribute('aria-expanded')).toBe('false');
    expect(document.activeElement).toBe(priceChip);

    await act(async () => {
      root?.render(
        <FilterPanel
          activeFilterCount={1}
          filters={{ ...EMPTY_FILTERS, priceEokMin: 8.5, priceEokMax: 15 }}
          onChange={onChange}
          onReset={() => onChange(EMPTY_FILTERS)}
        />,
      );
    });
    act(() => testHost.querySelector<HTMLButtonElement>('button[aria-label="마커 필터 초기화"]')?.click());
    expect(onChange).toHaveBeenLastCalledWith(EMPTY_FILTERS);
  });

  it('group 요약은 표시하고 적용 count 문구는 렌더링하지 않는다', async () => {
    const testHost = document.createElement('div');
    host = testHost;
    document.body.append(testHost);
    root = createRoot(testHost);

    await act(async () => {
      root?.render(
        <FilterPanel
          activeFilterCount={2}
          filters={{
            ...EMPTY_FILTERS,
            unitMin: 300,
            unitMax: 1200,
            priceEokMin: 8.5,
          }}
          onChange={vi.fn()}
          onReset={vi.fn()}
        />,
      );
    });

    expect(testHost.textContent).toContain('300–1,200세대');
    expect(testHost.textContent).toContain('8.5억 이상');
    expect(testHost.textContent).not.toContain('필터 2개 적용');
    expect(testHost.querySelector('.filter-status')).toBeNull();
    expect(testHost.querySelector('.filter-chip-list button[aria-label="마커 필터 초기화"]')).not.toBeNull();
  });

  it('outside click은 draft를 폐기하고 음수·역전 범위는 적용을 차단한다', async () => {
    const testHost = document.createElement('div');
    host = testHost;
    document.body.append(testHost);
    root = createRoot(testHost);
    const onChange = vi.fn();

    await act(async () => {
      root?.render(
        <FilterPanel
          activeFilterCount={0}
          filters={EMPTY_FILTERS}
          onChange={onChange}
          onReset={vi.fn()}
        />,
      );
    });

    act(() => testHost.querySelector<HTMLButtonElement>('button[aria-label="평형 필터 열기"]')?.click());
    act(() => {
      setInputValue(testHost.querySelector('input[aria-label="최소 평형"]'), '40');
      setInputValue(testHost.querySelector('input[aria-label="최대 평형"]'), '20');
      testHost.querySelector<HTMLButtonElement>('button[aria-label="평형 필터 적용"]')?.click();
    });

    expect(testHost.textContent).toContain('최소값은 최대값보다 클 수 없습니다');
    expect(onChange).not.toHaveBeenCalled();

    act(() => setInputValue(testHost.querySelector('input[aria-label="최소 평형"]'), '-1'));
    act(() => testHost.querySelector<HTMLButtonElement>('button[aria-label="평형 필터 적용"]')?.click());
    expect(testHost.textContent).toContain('0 이상을 입력해주세요');
    expect(onChange).not.toHaveBeenCalled();

    act(() => document.body.dispatchEvent(new Event('pointerdown', { bubbles: true })));
    expect(testHost.querySelector('button[aria-label="평형 필터 열기"]')).not.toBeNull();
    expect(onChange).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(
      testHost.querySelector<HTMLButtonElement>('button[aria-label="평형 필터 열기"]'),
    );
  });

  it('group 초기화는 해당 request field 두 개를 null로 반영한다', async () => {
    const testHost = document.createElement('div');
    host = testHost;
    document.body.append(testHost);
    root = createRoot(testHost);
    const onChange = vi.fn();
    const filters = { ...EMPTY_FILTERS, unitMin: 300, unitMax: 1200 };

    await act(async () => {
      root?.render(
        <FilterPanel
          activeFilterCount={1}
          filters={filters}
          onChange={onChange}
          onReset={vi.fn()}
        />,
      );
    });

    act(() => testHost.querySelector<HTMLButtonElement>('button[aria-label="세대수 필터 열기"]')?.click());
    act(() => testHost.querySelector<HTMLButtonElement>('button[aria-label="세대수 필터 초기화"]')?.click());

    expect(onChange).toHaveBeenCalledWith({ ...filters, unitMin: null, unitMax: null });
  });

  it('숫자 draft와 dual slider를 양방향 동기화하고 blank bound는 null로 유지한다', async () => {
    const testHost = document.createElement('div');
    host = testHost;
    document.body.append(testHost);
    root = createRoot(testHost);
    const onChange = vi.fn();

    await act(async () => {
      root?.render(
        <FilterPanel
          activeFilterCount={0}
          filters={EMPTY_FILTERS}
          onChange={onChange}
          onReset={vi.fn()}
        />,
      );
    });

    act(() => testHost.querySelector<HTMLButtonElement>('button[aria-label="가격 필터 열기"]')?.click());
    const minimum = testHost.querySelector<HTMLInputElement>('input[aria-label="최소 가격 억"]');
    const maximumSlider = testHost.querySelector<HTMLInputElement>('input[aria-label="최대 가격 슬라이더"]');
    expect(testHost.querySelectorAll('input[type="range"]')).toHaveLength(2);
    expect(maximumSlider?.value).toBe('80');

    act(() => setInputValue(minimum, '100'));
    expect(testHost.querySelector<HTMLInputElement>('input[aria-label="최소 가격 슬라이더"]')?.max).toBe('100');

    act(() => {
      setInputValue(minimum, '');
      setInputValue(maximumSlider, '42.5');
      testHost.querySelector<HTMLButtonElement>('button[aria-label="가격 필터 적용"]')?.click();
    });

    expect(onChange).toHaveBeenLastCalledWith({
      ...EMPTY_FILTERS,
      priceEokMin: null,
      priceEokMax: 42.5,
    });
  });
});

function setInputValue(input: HTMLInputElement | null, value: string) {
  if (!input) {
    return;
  }

  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
}
