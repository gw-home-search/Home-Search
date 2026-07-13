import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchComplexDetail, fetchComplexDetailByComplexId } from './fetchComplexDetail';
import { resolveApiUrl } from '../../map/api/resolveApiUrl';

describe('fetchComplexDetail API 어댑터', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('선택한 parcel의 documented detail data를 가져온다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        parcelId: '1001',
        complexId: '501',
        latitude: '37.5123',
        longitude: '127.0456',
        address: 'Sample address',
        tradeName: 'Sample trade name',
        displayName: '응봉동 Sample complex name',
        name: 'Sample complex name',
        dongCnt: '8',
        unitCnt: '740',
        platArea: '12345.67',
        archArea: '2345.67',
        totArea: '98765.43',
        bcRat: '22.5',
        vlRat: '199.8',
        useDate: '2015-03-20',
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchComplexDetail(1001)).resolves.toEqual({
      parcelId: 1001,
      complexId: 501,
      latitude: 37.5123,
      longitude: 127.0456,
      address: 'Sample address',
      tradeName: 'Sample trade name',
      displayName: '응봉동 Sample complex name',
      name: 'Sample complex name',
      dongCnt: 8,
      unitCnt: 740,
      platArea: 12345.67,
      archArea: 2345.67,
      totArea: 98765.43,
      bcRat: 22.5,
      vlRat: 199.8,
      useDate: '2015-03-20',
      prediction: null,
    });

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/detail/1001'),
      expect.objectContaining({ method: 'GET' }),
    );
  });

  it('complexId가 있으면 detail URL에 query parameter로 전달한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        parcelId: 1001,
        complexId: 502,
        latitude: 37.5123,
        longitude: 127.0456,
        address: 'Sample address',
        name: 'Selected complex',
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchComplexDetail(1001, 502)).resolves.toMatchObject({
      parcelId: 1001,
      complexId: 502,
      name: 'Selected complex',
    });

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/detail/1001?complexId=502'),
      expect.objectContaining({ method: 'GET' }),
    );
  });

  it('optional prediction READY 응답을 detail model로 normalize한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        parcelId: 1001,
        complexId: 501,
        latitude: 37.5123,
        longitude: 127.0456,
        address: 'Sample address',
        name: 'Sample complex name',
        prediction: {
          status: 'READY',
          modelVersion: 'deployment__F37_monthly_anchor_prev3_rolling_huber_010',
          predictedDealAmount: '179163',
          predictedPricePerM2: '2115.5',
          predictedPricePerPyeong: '6993.4',
          intervalLow: '139425',
          intervalHigh: '218900',
          intervalBasis: 'recent_holdout_p95',
          targetAreaM2: '84.69',
          targetFloor: '6',
          basisTradeId: '9001',
          basisDealDate: '2026-01-01',
          generatedAt: '2026-06-25T07:05:38Z',
          message: null,
        },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchComplexDetail(1001, 501)).resolves.toMatchObject({
      prediction: {
        status: 'READY',
        modelVersion: 'deployment__F37_monthly_anchor_prev3_rolling_huber_010',
        predictedDealAmount: 179163,
        predictedPricePerM2: 2115.5,
        predictedPricePerPyeong: 6993.4,
        intervalLow: 139425,
        intervalHigh: 218900,
        intervalBasis: 'recent_holdout_p95',
        targetAreaM2: 84.69,
        targetFloor: 6,
        basisTradeId: 9001,
        basisDealDate: '2026-01-01',
        generatedAt: '2026-06-25T07:05:38Z',
        message: null,
      },
    });
  });

  it('complexId 단독 detail URL을 호출한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        parcelId: 1001,
        complexId: 502,
        latitude: 37.6123,
        longitude: 127.1456,
        address: 'Sample address',
        name: 'Selected complex',
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchComplexDetailByComplexId(502)).resolves.toMatchObject({
      parcelId: 1001,
      complexId: 502,
      name: 'Selected complex',
    });

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/complex/502'),
      expect.objectContaining({ method: 'GET' }),
    );
  });

  it('coordinate-pending detail의 null 좌표를 보존한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({
          parcelId: 3001,
          complexId: 801,
          latitude: null,
          longitude: null,
          address: 'Coordinate pending address',
          name: 'Coordinate Pending Complex',
        }),
      ),
    );

    await expect(fetchComplexDetail(3001, 801)).resolves.toMatchObject({
      parcelId: 3001,
      complexId: 801,
      latitude: null,
      longitude: null,
      address: 'Coordinate pending address',
      name: 'Coordinate Pending Complex',
    });
  });

  it('주소가 없는 detail도 거래 UI를 막지 않도록 null address를 보존한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({
          parcelId: 4669,
          complexId: 4368,
          latitude: 37.567994,
          longitude: 126.9930772,
          address: null,
          tradeName: '힐스테이트세운센트럴1단지',
          name: '힐스테이트세운센트럴1단지',
        }),
      ),
    );

    await expect(fetchComplexDetail(4669, 4368)).resolves.toMatchObject({
      parcelId: 4669,
      complexId: 4368,
      address: null,
      name: '힐스테이트세운센트럴1단지',
    });
  });

  it('detail lookup 실패 시 public API ProblemDetail detail로 reject한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        errorResponse(404, {
          detail: 'Parcel not found.',
        }),
      ),
    );

    await expect(fetchComplexDetail(1001)).rejects.toThrow(
      'Failed to fetch complex detail: 404 Parcel not found.',
    );
  });
});

function jsonResponse(body: unknown): Response {
  return {
    ok: true,
    status: 200,
    json: () => Promise.resolve(body),
  } as Response;
}

function errorResponse(status: number, body: unknown): Response {
  return {
    ok: false,
    status,
    json: () => Promise.resolve(body),
  } as Response;
}
