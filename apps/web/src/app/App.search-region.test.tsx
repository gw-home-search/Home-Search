import { act } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  createFakeKakaoSdk,
  errorResponse,
  flushAsyncState,
  jsonResponse,
  renderApp,
  resetAppTestState,
  resolveApiUrl,
  submitForm,
  unmount,
  waitForMillis,
} from './appTestHarness';

describe('App 검색과 지역', () => {
  afterEach(resetAppTestState);

  it('초기 탐색 패널은 검색 입력과 지역 내비게이션을 기본으로 연다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([])));

    const { root, rootElement } = await renderApp();
    await flushAsyncState();

    const explorationPanel = rootElement.querySelector<HTMLElement>('#exploration-panel');
    const regionPanel = rootElement.querySelector<HTMLElement>('#exploration-panel-region');

    expect(rootElement.querySelector('[role="tablist"]')).toBeNull();
    expect(explorationPanel?.getAttribute('aria-label')).toBe('탐색 패널');
    expect(
      Array.from(explorationPanel?.querySelectorAll('p') ?? [])
        .some((element) => element.textContent?.trim() === '탐색'),
    ).toBe(false);
    expect(regionPanel?.getAttribute('aria-label')).toBe('지역 탐색 패널');
    expect(
      Array.from(regionPanel?.querySelectorAll('p') ?? [])
        .some((element) => element.textContent?.trim() === '지역'),
    ).toBe(false);
    expect(rootElement.querySelector('input[aria-label="단지 검색"]')).not.toBeNull();
    expect(rootElement.querySelector('#exploration-panel-region')?.hasAttribute('hidden')).toBe(false);

    unmount(root);
  });

  it('단지 입력은 debounce suggestion만 호출하고 submit 후 검색 목록 모드로 전환한다', async () => {
    const fetchMock = vi.fn((url: RequestInfo | URL) => {
      const requestUrl = String(url);

      if (requestUrl === resolveApiUrl('/api/v1/map/regions')) {
        return Promise.resolve(jsonResponse([]));
      }

      if (requestUrl === resolveApiUrl('/api/v1/search/complexes/suggestions?q=Sample')) {
        return Promise.resolve(jsonResponse([]));
      }

      if (requestUrl === resolveApiUrl('/api/v1/search/complexes?q=Sample')) {
        return Promise.resolve(jsonResponse([
          {
            complexId: 501,
            complexName: 'Sample Apartment',
            parcelId: 1001,
            latitude: 37.5123,
            longitude: 127.0456,
            address: 'Sample address',
          },
        ]));
      }

      return Promise.resolve(errorResponse(404));
    });
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp();
    await flushAsyncState();

    const searchInput = rootElement.querySelector<HTMLInputElement>(
      'input[aria-label="단지 검색"]',
    );
    await act(async () => {
      if (searchInput) {
        searchInput.value = 'Sample';
        searchInput.dispatchEvent(new Event('input', { bubbles: true }));
      }
    });
    await waitForMillis(350);
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/search/complexes/suggestions?q=Sample'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(fetchMock.mock.calls.some(([url]) => (
      String(url) === resolveApiUrl('/api/v1/search/complexes?q=Sample')
    ))).toBe(false);

    const searchForm = rootElement.querySelector<HTMLFormElement>('form[aria-label="단지 검색"]');
    await act(async () => submitForm(searchForm));
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/search/complexes?q=Sample'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(rootElement.querySelector<HTMLElement>('#exploration-panel')?.dataset.sidebarMode).toBe(
      'search',
    );
    expect(rootElement.querySelector('#exploration-panel-search')?.hasAttribute('hidden')).toBe(
      false,
    );
    expect(rootElement.querySelector('#exploration-panel-region')?.hasAttribute('hidden')).toBe(
      true,
    );
    expect(
      rootElement.querySelector('button[aria-label="검색 결과 선택 Sample Apartment"]'),
    ).not.toBeNull();

    unmount(root);
  });

  it('검색 실패는 결과 heading과 오류 제목을 중복해서 읽히지 않는다', async () => {
    const fetchMock = vi.fn((url: RequestInfo | URL) => {
      const requestUrl = String(url);
      if (requestUrl === resolveApiUrl('/api/v1/search/complexes?q=Sample')) {
        return Promise.resolve(errorResponse(500));
      }
      return Promise.resolve(jsonResponse([]));
    });
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp();
    await flushAsyncState();

    const searchInput = rootElement.querySelector<HTMLInputElement>('input[aria-label="단지 검색"]');
    const searchForm = rootElement.querySelector<HTMLFormElement>('form[aria-label="단지 검색"]');
    await act(async () => {
      if (searchInput) searchInput.value = 'Sample';
      submitForm(searchForm);
    });
    await flushAsyncState();

    expect(rootElement.textContent?.match(/검색 결과/g)).toHaveLength(1);
    expect(rootElement.textContent).toContain('입력한 검색어는 그대로 유지돼요.');

    unmount(root);
  });

  it('documented URL로 complex를 search하고 선택한 parcel detail을 연다', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      if (url === resolveApiUrl('/api/v1/search/complexes?q=Sample')) {
        return Promise.resolve(jsonResponse([
          {
            complexId: 501,
            complexName: 'Sample Apartment',
            parcelId: 1001,
            latitude: 37.5123,
            longitude: 127.0456,
            address: 'Sample address',
          },
        ]));
      }
      if (url === resolveApiUrl('/api/v1/detail/1001?complexId=501')) {
        return Promise.resolve(jsonResponse({
          parcelId: 1001,
          complexId: 501,
          latitude: 37.5123,
          longitude: 127.0456,
          address: 'Sample address',
          tradeName: 'Sample trade name',
          name: 'Sample complex name',
        }));
      }
      if (url === resolveApiUrl('/api/v1/complex/501/trade-areas')) {
        return Promise.resolve(jsonResponse({
          complexId: 501,
          defaultExclArea: 84.94,
          areas: [{ exclArea: 84.94, tradeCount: 1, latestDealDate: '2026-07-16' }],
        }));
      }
      if (url === resolveApiUrl('/api/v1/complex/501/trades?exclArea=84.94')) {
        return Promise.resolve(jsonResponse({
          parcelId: 1001,
          complexId: 501,
          content: [],
          page: 0,
          size: 20,
          totalElements: 0,
          totalPages: 0,
        }));
      }
      if (url === resolveApiUrl('/api/v1/detail/1001/complexes')) {
        return Promise.resolve(jsonResponse([
          {
            complexId: 501,
            complexName: 'Sample Apartment',
            parcelId: 1001,
            latitude: 37.5123,
            longitude: 127.0456,
            address: 'Sample address',
          },
        ]));
      }
      return Promise.resolve(jsonResponse([]));
    });
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp({ initialMapLevel: 4 });
    await flushAsyncState();

    const searchInput = rootElement.querySelector<HTMLInputElement>(
      'input[aria-label="단지 검색"]',
    );
    const searchButton = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="단지 검색 실행"]',
    );
    const searchForm = rootElement.querySelector<HTMLFormElement>(
      'form[aria-label="단지 검색"]',
    );
    expect(searchInput).not.toBeNull();
    expect(searchButton).not.toBeNull();
    expect(searchForm).not.toBeNull();

    await act(async () => {
      if (searchInput) {
        searchInput.value = 'Sample';
      }
      submitForm(searchForm);
    });
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/search/complexes?q=Sample'),
      expect.objectContaining({ method: 'GET' }),
    );

    const searchResult = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="검색 결과 선택 Sample Apartment"]',
    );
    expect(searchResult).not.toBeNull();
    expect(searchResult?.classList.contains('complex-list-row')).toBe(true);
    expect(searchResult?.closest('[data-ui-component="complex-list"]')).not.toBeNull();
    expect(searchResult?.querySelector('.complex-list-name')?.textContent).toBe('Sample Apartment');
    expect(searchResult?.querySelector('.complex-list-address')?.textContent).toBe('Sample address');

    await act(async () => {
      searchResult?.click();
    });
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/detail/1001?complexId=501'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/complex/501/trades?exclArea=84.94'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/detail/1001/complexes'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/map/complexes'),
      expect.objectContaining({
        method: 'POST',
        body: expect.stringContaining('"swLat":37.5023'),
      }),
    );
    expect(rootElement.querySelector('[aria-label="단지 상세 패널"]')).not.toBeNull();
    expect(rootElement.textContent).toContain('Sample complex name');

    unmount(root);
  });

  it('좌표 대기 search result도 complexId scope를 유지하고 detail/trade sidebar를 연다', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      if (url === resolveApiUrl('/api/v1/search/complexes?q=pending')) {
        return Promise.resolve(jsonResponse([
          {
            complexId: 801,
            complexName: 'Coordinate Pending Complex',
            parcelId: 3001,
            latitude: null,
            longitude: null,
            address: 'Coordinate pending address',
          },
        ]));
      }
      if (url === resolveApiUrl('/api/v1/detail/3001?complexId=801')) {
        return Promise.resolve(jsonResponse({
          parcelId: 3001,
          complexId: 801,
          latitude: null,
          longitude: null,
          address: 'Coordinate pending address',
          tradeName: 'Coordinate Pending Trade',
          name: 'Coordinate Pending Complex',
        }));
      }
      if (url === resolveApiUrl('/api/v1/complex/801/trade-areas')) {
        return Promise.resolve(jsonResponse({
          complexId: 801,
          defaultExclArea: 84.94,
          areas: [{ exclArea: 84.94, tradeCount: 1, latestDealDate: '2026-07-16' }],
        }));
      }
      if (url === resolveApiUrl('/api/v1/complex/801/trades?exclArea=84.94')) {
        return Promise.resolve(jsonResponse({
          parcelId: 3001,
          complexId: 801,
          content: [],
          page: 0,
          size: 20,
          totalElements: 0,
          totalPages: 0,
        }));
      }
      if (url === resolveApiUrl('/api/v1/detail/3001/complexes')) {
        return Promise.resolve(jsonResponse([
          {
            complexId: 801,
            complexName: 'Coordinate Pending Complex',
            parcelId: 3001,
            latitude: null,
            longitude: null,
            address: 'Coordinate pending address',
          },
        ]));
      }
      return Promise.resolve(jsonResponse([]));
    });
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp();
    await flushAsyncState();

    const searchInput = rootElement.querySelector<HTMLInputElement>(
      'input[aria-label="단지 검색"]',
    );
    const searchForm = rootElement.querySelector<HTMLFormElement>(
      'form[aria-label="단지 검색"]',
    );

    await act(async () => {
      if (searchInput) {
        searchInput.value = 'pending';
      }
      submitForm(searchForm);
    });
    await flushAsyncState();

    const searchResult = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="검색 결과 선택 Coordinate Pending Complex"]',
    );
    expect(searchResult).not.toBeNull();

    await act(async () => {
      searchResult?.click();
    });
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/detail/3001?complexId=801'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/complex/801/trades?exclArea=84.94'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/complex/801/trade-areas'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(rootElement.querySelector('[aria-label="단지 상세 패널"]')).not.toBeNull();
    expect(rootElement.textContent).toContain('Coordinate Pending Complex');
    expect(rootElement.textContent).toContain('거래 내역이 없습니다');

    unmount(root);
  });

  it('children이 있는 시도·시군구 단계에서는 단지 목록을 요청하거나 표시하지 않는다', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse([]))
      .mockResolvedValueOnce(
        jsonResponse([
          {
            id: 1,
            name: 'Seoul',
            code: '11',
          },
        ]),
      )
      .mockResolvedValueOnce(
        jsonResponse({
          id: 1,
          name: 'Seoul',
          code: '11',
          latitude: 37.5663,
          longitude: 126.978,
          children: [
            {
              id: 11,
              name: 'Gangnam-gu',
              code: '11680',
            },
          ],
        }),
      )
      .mockResolvedValueOnce(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp();
    await flushAsyncState();

    const loadRegionsButton = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="지역 처음으로"]',
    );
    expect(loadRegionsButton).not.toBeNull();

    await act(async () => {
      loadRegionsButton?.click();
    });
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/region'),
      expect.objectContaining({ method: 'GET' }),
    );

    const regionButton = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="지역 이동 Seoul"]',
    );
    expect(regionButton).not.toBeNull();

    await act(async () => {
      regionButton?.click();
    });
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/region/1'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(fetchMock).not.toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/region/1/complexes?limit=20&offset=0'),
      expect.anything(),
    );
    expect(fetchMock).toHaveBeenLastCalledWith(
      resolveApiUrl('/api/v1/map/regions'),
      expect.objectContaining({
        method: 'POST',
        body: expect.stringContaining('"region":"si-gun-gu"'),
      }),
    );
    expect(rootElement.textContent).toContain('Gangnam-gu');
    expect(rootElement.textContent).not.toContain('Region Complex');

    unmount(root);
  });

  it('탐색 패널은 지역 목록을 바로 불러오고 단계적으로 drill-down한다', async () => {
    const fetchMock = vi.fn((url: RequestInfo | URL) => {
      const requestUrl = String(url);

      if (requestUrl === resolveApiUrl('/api/v1/map/complexes')) {
        return Promise.resolve(jsonResponse([]));
      }

      if (requestUrl === resolveApiUrl('/api/v1/map/regions')) {
        return Promise.resolve(jsonResponse([]));
      }

      if (requestUrl === resolveApiUrl('/api/v1/region')) {
        return Promise.resolve(jsonResponse([{ id: 1, name: 'Seoul', code: '11' }]));
      }

      if (requestUrl === resolveApiUrl('/api/v1/region/1')) {
        return Promise.resolve(jsonResponse({
          id: 1,
          name: 'Seoul',
          code: '11',
          latitude: 37.5663,
          longitude: 126.978,
          children: [{ id: 11, name: 'Gangnam-gu', code: '11680' }],
        }));
      }

      if (requestUrl === resolveApiUrl('/api/v1/region/1/complexes?limit=20&offset=0')) {
        return Promise.resolve(jsonResponse([]));
      }

      if (requestUrl === resolveApiUrl('/api/v1/region/11')) {
        return Promise.resolve(jsonResponse({
          id: 11,
          name: 'Gangnam-gu',
          code: '11680',
          latitude: 37.5172,
          longitude: 127.0473,
          children: [{ id: 111, name: 'Apgujeong-dong', code: '11680110' }],
        }));
      }

      if (requestUrl === resolveApiUrl('/api/v1/region/11/complexes?limit=20&offset=0')) {
        return Promise.resolve(jsonResponse([]));
      }

      if (requestUrl === resolveApiUrl('/api/v1/region/111')) {
        return Promise.resolve(jsonResponse({
          id: 111,
          name: 'Apgujeong-dong',
          code: '11680110',
          latitude: 37.5271,
          longitude: 127.0287,
          children: [],
        }));
      }

      if (requestUrl === resolveApiUrl('/api/v1/region/111/complexes?limit=20&offset=0')) {
        return Promise.resolve(jsonResponse([
          {
            complexId: 901,
            complexName: 'Apgujeong Region Complex',
            parcelId: 3001,
            latitude: 37.5271,
            longitude: 127.0287,
            address: 'Apgujeong address',
            unitCnt: 810,
            dongCnt: 12,
            useDate: '2018-04-20',
          },
        ]));
      }

      return Promise.resolve(errorResponse(404));
    });
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp({ initialRegionLoad: true });
    await flushAsyncState();
    await flushAsyncState();

    expect(rootElement.querySelector('[role="tablist"]')).toBeNull();
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/region'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(rootElement.textContent).toContain('시도 선택');
    expect(rootElement.querySelector('button[aria-label="지역 처음으로"]')?.getAttribute('aria-current')).toBe('page');
    expect(rootElement.querySelector('.region-step-summary')).toBeNull();

    const sidoButton = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="지역 이동 Seoul"]',
    );
    await act(async () => {
      sidoButton?.click();
    });
    await flushAsyncState();

    expect(rootElement.textContent).toContain('Seoul');
    expect(rootElement.querySelector('button[aria-label="지역 처음으로"]')?.hasAttribute('aria-current')).toBe(false);
    expect(rootElement.textContent).not.toContain('시군구 선택');
    expect(rootElement.querySelector('button[aria-label="지역 단계 이동 Seoul"]')).not.toBeNull();
    expect(fetchMock).not.toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/region/1/complexes?limit=20&offset=0'),
      expect.anything(),
    );
    expect(fetchMock).toHaveBeenLastCalledWith(
      resolveApiUrl('/api/v1/map/regions'),
      expect.objectContaining({
        method: 'POST',
        body: expect.stringContaining('"region":"si-gun-gu"'),
      }),
    );

    const sigunguButton = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="지역 이동 Gangnam-gu"]',
    );
    await act(async () => {
      sigunguButton?.click();
    });
    await flushAsyncState();

    expect(rootElement.textContent).toContain('Gangnam-gu');
    expect(rootElement.textContent).not.toContain('읍면동 선택');
    expect(fetchMock).not.toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/region/11/complexes?limit=20&offset=0'),
      expect.anything(),
    );
    expect(fetchMock).toHaveBeenLastCalledWith(
      resolveApiUrl('/api/v1/map/regions'),
      expect.objectContaining({
        method: 'POST',
        body: expect.stringContaining('"region":"eup-myeon-dong"'),
      }),
    );

    const emdButton = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="지역 이동 Apgujeong-dong"]',
    );
    await act(async () => {
      emdButton?.click();
    });
    await flushAsyncState();

    expect(rootElement.textContent).toContain('Apgujeong Region Complex');
    const regionComplexCard = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="지역 단지 선택 Apgujeong Region Complex"]',
    );
    expect(regionComplexCard?.classList.contains('complex-list-row')).toBe(true);
    expect(regionComplexCard?.closest('[data-ui-component="complex-list"]')).not.toBeNull();
    expect(regionComplexCard?.querySelector('.complex-list-name')?.textContent).toBe('Apgujeong Region Complex');
    expect(regionComplexCard?.querySelector('.complex-list-address')?.textContent).toBe('Apgujeong address');
    expect(regionComplexCard?.querySelector('.complex-list-context')?.textContent).toContain('2018년 승인');
    expect(regionComplexCard?.querySelector('.complex-list-unit')?.textContent).toBe('810세대');
    expect(regionComplexCard?.querySelector('.complex-list-building')?.textContent).toBe('12동');
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/region/111/complexes?limit=20&offset=0'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(fetchMock).toHaveBeenLastCalledWith(
      resolveApiUrl('/api/v1/map/complexes'),
      expect.objectContaining({ method: 'POST' }),
    );

    await act(async () => {
      rootElement.querySelector<HTMLButtonElement>('button[aria-label="지역 단계 이동 Seoul"]')?.click();
    });
    await flushAsyncState();

    expect(rootElement.querySelector('button[aria-label="지역 단계 이동 Seoul"]')).not.toBeNull();
    expect(rootElement.querySelector('button[aria-label="지역 단계 이동 Gangnam-gu"]')).toBeNull();
    expect(rootElement.textContent).not.toContain('Apgujeong Region Complex');
    expect(rootElement.querySelector('button[aria-label="지역 이동 Gangnam-gu"]')).not.toBeNull();

    expect(rootElement.querySelector('input[aria-label="단지 검색"]')).not.toBeNull();

    unmount(root);
  });

  it('지역 첫 로드 실패는 존재하지 않는 이전 데이터를 언급하지 않는다', async () => {
    const fetchMock = vi.fn((url: RequestInfo | URL) => {
      if (String(url) === resolveApiUrl('/api/v1/region')) {
        return Promise.resolve(errorResponse(500));
      }
      return Promise.resolve(jsonResponse([]));
    });
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp({ initialRegionLoad: true });
    await flushAsyncState();
    await flushAsyncState();

    expect(rootElement.textContent).toContain('지도와 검색은 계속 사용할 수 있어요.');
    expect(rootElement.textContent).not.toContain('이전에 불러온 지역은 계속 볼 수 있어요.');

    unmount(root);
  });

  it('기존 지역을 유지한 갱신 실패에서만 이전 지역 안내를 제공한다', async () => {
    let rootRegionRequests = 0;
    const fetchMock = vi.fn((url: RequestInfo | URL) => {
      if (String(url) === resolveApiUrl('/api/v1/region')) {
        rootRegionRequests += 1;
        return rootRegionRequests === 1
          ? Promise.resolve(jsonResponse([{ id: 1, name: 'Seoul', code: '11' }]))
          : Promise.resolve(errorResponse(500));
      }
      return Promise.resolve(jsonResponse([]));
    });
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp({ initialRegionLoad: true });
    await flushAsyncState();
    await flushAsyncState();

    await act(async () => {
      rootElement.querySelector<HTMLButtonElement>('button[aria-label="지역 처음으로"]')?.click();
    });
    await flushAsyncState();
    await flushAsyncState();

    expect(rootElement.textContent).toContain('Seoul');
    expect(rootElement.textContent).toContain('이전에 불러온 지역은 계속 볼 수 있어요.');

    unmount(root);
  });

  it('단지 검색 입력은 suggestion API를 사용하고 suggestion 선택으로 detail을 연다', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse([]))
      .mockResolvedValueOnce(
        jsonResponse([
          {
            complexId: 501,
            complexName: 'Suggested Apartment',
            parcelId: 1001,
            address: 'Suggestion address',
          },
        ]),
      )
      .mockResolvedValueOnce(
        jsonResponse({
          parcelId: 1001,
          complexId: 501,
          latitude: 37.5123,
          longitude: 127.0456,
          address: 'Suggestion address',
          name: 'Suggested Apartment',
        }),
      )
      .mockResolvedValueOnce(
        jsonResponse({
          parcelId: 1001,
          complexId: 501,
          content: [],
          page: 0,
          size: 20,
          totalElements: 0,
          totalPages: 0,
        }),
      )
      .mockResolvedValueOnce(jsonResponse([]))
      .mockResolvedValueOnce(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp();
    await flushAsyncState();

    const searchInput = rootElement.querySelector<HTMLInputElement>(
      'input[aria-label="단지 검색"]',
    );
    await act(async () => {
      if (searchInput) {
        searchInput.value = 'Suggested';
        searchInput.dispatchEvent(new Event('input', { bubbles: true }));
      }
    });
    await waitForMillis(350);
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/search/complexes/suggestions?q=Suggested'),
      expect.objectContaining({ method: 'GET' }),
    );
    const suggestion = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="검색 제안 선택 Suggested Apartment"]',
    );
    expect(suggestion).not.toBeNull();

    await act(async () => {
      suggestion?.click();
    });
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/detail/1001?complexId=501'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(rootElement.textContent).toContain('Suggested Apartment');

    unmount(root);
  });

  it('key가 없어도 대체 지도 위에 지역 marker를 표시하고 바로 다음 단계로 이동한다', async () => {
    const fetchMock = vi.fn((url: RequestInfo | URL) => {
      if (String(url) === resolveApiUrl('/api/v1/map/regions')) {
        return Promise.resolve(jsonResponse([
          {
            id: 1,
            name: 'Seoul',
            lat: 37.5663,
            lng: 126.978,
            unitCntSum: 1200,
          },
        ]));
      }
      if (String(url) === resolveApiUrl('/api/v1/region/1')) {
        return Promise.resolve(jsonResponse({ id: 1, name: 'Seoul', code: '11', latitude: 37.5663, longitude: 126.978, children: [{ id: 11, name: 'Gangnam-gu', code: '11680' }] }));
      }

      return Promise.resolve(jsonResponse([]));
    });
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp({ kakaoMapAppKey: '' });
    await flushAsyncState();
    await flushAsyncState();

    const fallbackMarkerLayer = rootElement.querySelector('[aria-label="대체 지도 마커"]');
    const fallbackMarker = rootElement.querySelector<HTMLButtonElement>(
      '[data-fallback-marker-id="region-1"]',
    );
    expect(fallbackMarkerLayer).not.toBeNull();
    expect(fallbackMarker?.textContent).toContain('Seoul');
    expect(fallbackMarker?.textContent).toContain('1,200세대');

    await act(async () => {
      fallbackMarker?.click();
    });
    await flushAsyncState();
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/region/1'),
      expect.objectContaining({ method: 'GET', signal: expect.any(AbortSignal) }),
    );
    expect(rootElement.querySelector('[aria-label="지역 단계"]')?.textContent).toContain('Seoul');

    unmount(root);
  });

  it('현재 상세의 child가 아닌 지도 지역 marker를 누르면 잘못된 breadcrumb를 이어 붙이지 않는다', async () => {
    const fetchMock = vi.fn((url: RequestInfo | URL) => {
      const requestUrl = String(url);
      if (requestUrl === resolveApiUrl('/api/v1/region')) {
        return Promise.resolve(jsonResponse([{ id: 1, name: 'Seoul', code: '11' }]));
      }
      if (requestUrl === resolveApiUrl('/api/v1/region/1')) {
        return Promise.resolve(jsonResponse({
          id: 1,
          name: 'Seoul',
          code: '11',
          latitude: 37.5663,
          longitude: 126.978,
          children: [{ id: 11, name: 'Gangnam-gu', code: '11680' }],
        }));
      }
      if (requestUrl === resolveApiUrl('/api/v1/region/26')) {
        return Promise.resolve(jsonResponse({
          id: 26,
          name: 'Busan',
          code: '26',
          latitude: 35.1796,
          longitude: 129.0756,
          children: [{ id: 261, name: 'Haeundae-gu', code: '26350' }],
        }));
      }
      if (requestUrl === resolveApiUrl('/api/v1/map/regions')) {
        return Promise.resolve(jsonResponse([{
          id: 26,
          name: 'Busan',
          lat: 35.1796,
          lng: 129.0756,
          unitCntSum: 900,
        }]));
      }
      return Promise.resolve(jsonResponse([]));
    });
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp({ initialRegionLoad: true, kakaoMapAppKey: '' });
    await flushAsyncState();
    await flushAsyncState();

    await act(async () => {
      rootElement.querySelector<HTMLButtonElement>('button[aria-label="지역 이동 Seoul"]')?.click();
    });
    await flushAsyncState();
    expect(rootElement.querySelector('[aria-label="지역 단계"]')?.textContent).toContain('Seoul');

    const unrelatedMapMarker = rootElement.querySelector<HTMLButtonElement>(
      '[data-fallback-marker-id="region-26"]',
    );
    expect(unrelatedMapMarker).not.toBeNull();
    await act(async () => unrelatedMapMarker?.click());
    await flushAsyncState();
    await flushAsyncState();

    const breadcrumb = rootElement.querySelector('[aria-label="지역 단계"]');
    expect(breadcrumb?.textContent).toContain('Busan');
    expect(breadcrumb?.textContent).not.toContain('Seoul');

    unmount(root);
  });

  it('지역 marker 세대수가 없으면 0세대 대신 세대수 없음으로 표시한다', async () => {
    const fetchMock = vi.fn((url: RequestInfo | URL) => {
      if (String(url) === resolveApiUrl('/api/v1/map/regions')) {
        return Promise.resolve(jsonResponse([
          {
            id: 1,
            name: 'Seoul',
            lat: 37.5663,
            lng: 126.978,
            unitCntSum: null,
          },
        ]));
      }

      return Promise.resolve(jsonResponse([]));
    });
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp({ kakaoMapAppKey: '' });
    await flushAsyncState();
    await flushAsyncState();

    const fallbackMarker = rootElement.querySelector<HTMLButtonElement>(
      '[data-fallback-marker-id="region-1"]',
    );
    expect(fallbackMarker?.textContent).toContain('세대수 없음');
    expect(fallbackMarker?.textContent).not.toContain('0세대');

    unmount(root);
  });

  it('Kakao CustomOverlay region marker 클릭은 바로 다음 지도 단계로 이동한다', async () => {
    const fetchMock = vi.fn((url: RequestInfo | URL) => {
      const requestUrl = String(url);
      if (requestUrl === resolveApiUrl('/api/v1/map/regions')) return Promise.resolve(jsonResponse([
          {
            id: 1,
            name: 'Seoul',
            lat: 37.5663,
            lng: 126.978,
            unitCntSum: 1200,
          },
        ]));
      if (requestUrl === resolveApiUrl('/api/v1/region/1')) return Promise.resolve(jsonResponse({ id: 1, name: 'Seoul', code: '11', latitude: 37.5663, longitude: 126.978, children: [{ id: 11, name: 'Gangnam-gu', code: '11680' }] }));
      return Promise.resolve(jsonResponse([]));
    });
    const sdk = createFakeKakaoSdk({
      bounds: {
        swLat: 37.45,
        swLng: 126.85,
        neLat: 37.7,
        neLng: 127.2,
      },
      level: 10,
    });
    vi.stubGlobal('fetch', fetchMock);
    vi.stubGlobal('kakao', sdk.kakao);

    const { root, rootElement } = await renderApp({ initialMapLevel: 10 });
    await flushAsyncState();
    await flushAsyncState();

    const regionOverlayButton = sdk.overlays[0]?.content as HTMLButtonElement | undefined;
    expect(regionOverlayButton).not.toBeNull();
    expect(regionOverlayButton?.getAttribute('aria-label')).toBe('지역 이동 Seoul');
    expect(regionOverlayButton?.dataset.markerDensity).toBe('standard');
    expect(regionOverlayButton?.textContent).toContain('1,200세대');
    expect(rootElement.querySelector('[aria-label="지역 마커"]')).toBeNull();

    await act(async () => {
      regionOverlayButton?.click();
    });
    await flushAsyncState();
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/region/1'),
      expect.objectContaining({ method: 'GET', signal: expect.any(AbortSignal) }),
    );
    expect(rootElement.querySelector('[aria-label="지역 단계"]')?.textContent).toContain('Seoul');
    expect(sdk.map.setCenter).toHaveBeenCalled();
    expect(sdk.map.setLevel).toHaveBeenLastCalledWith(9);
    expect(fetchMock).toHaveBeenLastCalledWith(
      resolveApiUrl('/api/v1/map/regions'),
      expect.objectContaining({
        method: 'POST',
        body: expect.stringContaining('"region":"si-gun-gu"'),
      }),
    );

    unmount(root);
  });
});
