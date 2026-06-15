import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchComplexTrades, fetchParcelTrades } from './fetchParcelTrades';
import { resolveApiUrl } from '../../map/api/resolveApiUrl';

describe('fetchParcelTrades API 어댑터', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('선택한 parcel의 documented trade page를 가져온다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        parcelId: '1001',
        complexId: '501',
        content: [
          {
            tradeId: '9001',
            dealDate: '2025-12-01',
            exclArea: '84.93',
            dealAmount: '125000',
            aptDong: '101',
            floor: '12',
          },
        ],
        page: '0',
        size: '20',
        totalElements: '1',
        totalPages: '1',
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchParcelTrades(1001)).resolves.toEqual({
      parcelId: 1001,
      complexId: 501,
      trades: [
        {
          tradeId: 9001,
          dealDate: '2025-12-01',
          exclArea: 84.93,
          dealAmount: 125000,
          aptDong: '101',
          floor: 12,
        },
      ],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    });

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/trade/1001'),
      expect.objectContaining({ method: 'GET' }),
    );
  });

  it('valid empty trade page를 empty array로 유지한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({
          parcelId: 1001,
          complexId: null,
          content: [],
          page: 0,
          size: 20,
          totalElements: 0,
          totalPages: 0,
        }),
      ),
    );

    await expect(fetchParcelTrades(1001)).resolves.toEqual({
      parcelId: 1001,
      complexId: null,
      trades: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
    });
  });

  it('complexId가 있으면 trade URL에 query parameter로 전달한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(emptyTradePage(1001, 502)),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchParcelTrades(1001, 502)).resolves.toMatchObject({
      parcelId: 1001,
      complexId: 502,
      trades: [],
    });

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/trade/1001?complexId=502'),
      expect.objectContaining({ method: 'GET' }),
    );
  });

  it('page/size 옵션을 trade URL의 query parameter로 전달한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(emptyTradePage(1001, 502)),
    );
    vi.stubGlobal('fetch', fetchMock);

    await fetchParcelTrades(1001, 502, { page: 2, size: 5 });

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/trade/1001?complexId=502&page=2&size=5'),
      expect.objectContaining({ method: 'GET' }),
    );
  });

  it('complexId 단독 trade URL을 page 옵션과 함께 호출한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(emptyTradePage(1001, 502)),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchComplexTrades(502, { page: 1, size: 5 })).resolves.toMatchObject({
      parcelId: 1001,
      complexId: 502,
      trades: [],
    });

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/complex/502/trades?page=1&size=5'),
      expect.objectContaining({ method: 'GET' }),
    );
  });

  it('invalid trade item object를 reject한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({
          parcelId: 1001,
          content: [null],
        }),
      ),
    );

    await expect(fetchParcelTrades(1001)).rejects.toThrow(
      'Invalid public API parcel trade response: trade item must be an object',
    );
  });

  it('trade lookup 실패 시 public API ProblemDetail detail로 reject한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        errorResponse(404, {
          detail: 'Parcel not found.',
        }),
      ),
    );

    await expect(fetchParcelTrades(1001)).rejects.toThrow(
      'Failed to fetch parcel trades: 404 Parcel not found.',
    );
  });
});

function emptyTradePage(parcelId: number, complexId: number | null) {
  return {
    parcelId,
    complexId,
    content: [],
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0,
  };
}

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
