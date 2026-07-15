import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { MapViewport } from '../../app/mapAppTypes';
import { MAX_COMPLEX_MARKER_LEVEL } from '../map/api/fetchMapMarkers';
import type { NearbyPlaceCategory } from './api/fetchNearbyPlaces';
import { useViewportNearbyPlaces } from './useViewportNearbyPlaces';

const VIEWPORT: MapViewport = {
  bounds: { swLat: 37.45, swLng: 126.85, neLat: 37.50, neLng: 126.93 },
  level: 4,
};

function Probe({ categories, enabled, viewport }: {
  categories: NearbyPlaceCategory[];
  enabled: boolean;
  viewport: MapViewport;
}) {
  const result = useViewportNearbyPlaces(viewport, categories, enabled);
  return <output data-state={result.state}>{result.data.map((item) => item.category.category).join(',')}</output>;
}

describe('viewport 주변시설 조회 상태', () => {
  let root: Root | null = null;
  let host: HTMLDivElement | null = null;

  afterEach(() => {
    if (root) act(() => root?.unmount());
    root = null;
    host?.remove();
    host = null;
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('단지 marker level보다 넓으면 호출하지 않고 상세 level에서 선택 category만 병렬 조회한다', async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn((_input: RequestInfo | URL, init?: RequestInit) => {
      const category = JSON.parse(String(init?.body)).category as NearbyPlaceCategory;
      return Promise.resolve(response(category));
    });
    vi.stubGlobal('fetch', fetchMock);
    host = document.createElement('div');
    root = createRoot(host);

    await act(async () => root?.render(<Probe categories={['SUPERMARKET', 'HOSPITAL']} enabled viewport={{ ...VIEWPORT, level: MAX_COMPLEX_MARKER_LEVEL + 1 }} />));
    expect(host.querySelector('output')?.dataset.state).toBe('zoom-required');
    expect(fetchMock).not.toHaveBeenCalled();

    await act(async () => root?.render(<Probe categories={['SUPERMARKET', 'HOSPITAL']} enabled viewport={{ ...VIEWPORT, level: MAX_COMPLEX_MARKER_LEVEL }} />));
    expect(fetchMock).not.toHaveBeenCalled();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(400);
    });

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls.map((call) => JSON.parse(String(call[1]?.body)).category)).toEqual(['SUPERMARKET', 'HOSPITAL']);
    expect(host.querySelector('output')?.dataset.state).toBe('ready');
    expect(host.querySelector('output')?.textContent).toBe('SUPERMARKET,HOSPITAL');
  });

  it('선택 category가 0개면 enabled 상태여도 API를 호출하지 않는다', async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    host = document.createElement('div');
    root = createRoot(host);

    await act(async () => root?.render(<Probe categories={[]} enabled viewport={VIEWPORT} />));
    await act(async () => { await vi.advanceTimersByTimeAsync(500); });

    expect(fetchMock).not.toHaveBeenCalled();
    expect(host.querySelector('output')?.dataset.state).toBe('idle');
  });

  it('진행 중인 기존 category 요청을 재사용하고 새로 추가한 category만 조회한다', async () => {
    vi.useFakeTimers();
    let resolveCafe: ((value: Response) => void) | null = null;
    const cafeResponse = new Promise<Response>((resolve) => { resolveCafe = resolve; });
    const fetchMock = vi.fn((_, init?: RequestInit) => {
      const category = JSON.parse(String(init?.body)).category as NearbyPlaceCategory;
      return category === 'CAFE' ? cafeResponse : Promise.resolve(response(category));
    });
    vi.stubGlobal('fetch', fetchMock);
    host = document.createElement('div');
    root = createRoot(host);

    await act(async () => root?.render(<Probe categories={['CAFE']} enabled viewport={VIEWPORT} />));
    await act(async () => { await vi.advanceTimersByTimeAsync(400); });
    const cafeSignal = (fetchMock.mock.calls[0]?.[1] as RequestInit | undefined)?.signal;

    await act(async () => root?.render(<Probe categories={['CAFE', 'RESTAURANT']} enabled viewport={VIEWPORT} />));
    expect(cafeSignal?.aborted).toBe(false);
    await act(async () => { await vi.advanceTimersByTimeAsync(400); });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls.map((call) => JSON.parse(String(call[1]?.body)).category)).toEqual(['CAFE', 'RESTAURANT']);

    await act(async () => { resolveCafe?.(response('CAFE')); });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(host.querySelector('output')?.textContent).toBe('CAFE,RESTAURANT');
  });

  it('해제한 category overlay를 즉시 제거하고 해당 browser request만 abort한다', async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn((_input: RequestInfo | URL, _init?: RequestInit) => (
      new Promise<Response>(() => undefined)
    ));
    vi.stubGlobal('fetch', fetchMock);
    host = document.createElement('div');
    root = createRoot(host);

    await act(async () => root?.render(<Probe categories={['SUPERMARKET', 'HOSPITAL']} enabled viewport={VIEWPORT} />));
    await act(async () => { await vi.advanceTimersByTimeAsync(400); });
    const supermarketSignal = (fetchMock.mock.calls[0]?.[1] as RequestInit | undefined)?.signal;
    const hospitalSignal = (fetchMock.mock.calls[1]?.[1] as RequestInit | undefined)?.signal;

    await act(async () => root?.render(<Probe categories={['SUPERMARKET']} enabled viewport={VIEWPORT} />));

    expect(supermarketSignal?.aborted).toBe(false);
    expect(hospitalSignal?.aborted).toBe(true);
    expect(host.querySelector('output')?.textContent).not.toContain('HOSPITAL');
  });

  it('새 viewport를 갱신하는 동안에도 해제한 이전 화면 category를 즉시 제거한다', async () => {
    vi.useFakeTimers();
    const pendingResponse = new Promise<Response>(() => undefined);
    const fetchMock = vi.fn((_input: RequestInfo | URL, init?: RequestInit) => {
      const category = JSON.parse(String(init?.body)).category as NearbyPlaceCategory;
      return fetchMock.mock.calls.length <= 2 ? Promise.resolve(response(category)) : pendingResponse;
    });
    vi.stubGlobal('fetch', fetchMock);
    host = document.createElement('div');
    root = createRoot(host);

    await act(async () => root?.render(<Probe categories={['SUPERMARKET', 'HOSPITAL']} enabled viewport={VIEWPORT} />));
    await act(async () => { await vi.advanceTimersByTimeAsync(400); });
    expect(host.querySelector('output')?.textContent).toBe('SUPERMARKET,HOSPITAL');

    const nextViewport = {
      ...VIEWPORT,
      bounds: { ...VIEWPORT.bounds, swLat: VIEWPORT.bounds.swLat + 0.01 },
    };
    await act(async () => root?.render(<Probe categories={['SUPERMARKET', 'HOSPITAL']} enabled viewport={nextViewport} />));
    await act(async () => root?.render(<Probe categories={['SUPERMARKET']} enabled viewport={nextViewport} />));

    expect(host.querySelector('output')?.textContent).toBe('SUPERMARKET');
  });

  it('늦게 도착한 이전 viewport 응답으로 현재 category를 덮어쓰지 않는다', async () => {
    vi.useFakeTimers();
    let resolveCafe: ((value: Response) => void) | null = null;
    const cafeResponse = new Promise<Response>((resolve) => { resolveCafe = resolve; });
    const fetchMock = vi.fn()
      .mockReturnValueOnce(cafeResponse)
      .mockResolvedValueOnce(response('RESTAURANT'));
    vi.stubGlobal('fetch', fetchMock);
    host = document.createElement('div');
    root = createRoot(host);

    await act(async () => root?.render(<Probe categories={['CAFE']} enabled viewport={VIEWPORT} />));
    await act(async () => { await vi.advanceTimersByTimeAsync(400); });
    await act(async () => root?.render(<Probe categories={['RESTAURANT']} enabled viewport={VIEWPORT} />));
    await act(async () => { await vi.advanceTimersByTimeAsync(400); });
    expect(host.querySelector('output')?.textContent).toBe('RESTAURANT');

    await act(async () => { resolveCafe?.(response('CAFE')); });
    expect(host.querySelector('output')?.textContent).toBe('RESTAURANT');
  });

  it('viewport가 바뀌면 이전 화면의 진행 중 요청을 abort한다', async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn((_input: RequestInfo | URL, _init?: RequestInit) => (
      new Promise<Response>(() => undefined)
    ));
    vi.stubGlobal('fetch', fetchMock);
    host = document.createElement('div');
    root = createRoot(host);

    await act(async () => root?.render(<Probe categories={['CAFE']} enabled viewport={VIEWPORT} />));
    await act(async () => { await vi.advanceTimersByTimeAsync(400); });
    const previousSignal = (fetchMock.mock.calls[0]?.[1] as RequestInit | undefined)?.signal;

    const nextViewport = {
      ...VIEWPORT,
      bounds: { ...VIEWPORT.bounds, swLat: VIEWPORT.bounds.swLat + 0.01 },
    };
    await act(async () => root?.render(<Probe categories={['CAFE']} enabled viewport={nextViewport} />));

    expect(previousSignal?.aborted).toBe(true);
  });
});

function response(category: NearbyPlaceCategory): Response {
  return new Response(JSON.stringify({
    bounds: VIEWPORT.bounds,
    level: 4,
    source: { provider: 'KAKAO_LOCAL', countBasis: 'PROVIDER_SEARCH' },
    generatedAt: '2026-07-15T04:00:00Z',
    category: {
      category,
      label: category === 'CAFE' ? '카페' : '음식점',
      retrievedAt: '2026-07-15T03:59:50Z',
      places: [{
        placeId: `kakao:${category}`,
        name: '장소',
        categoryDetail: null,
        lat: 37.49,
        lng: 126.91,
        distanceMeters: 100,
        address: null,
        roadAddress: null,
        phone: null,
        placeUrl: null,
      }],
    },
  }), { status: 200 });
}
