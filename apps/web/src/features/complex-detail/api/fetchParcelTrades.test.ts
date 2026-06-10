import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchComplexTrades, fetchParcelTrades } from './fetchParcelTrades';
import { resolveApiUrl } from '../../map/api/resolveApiUrl';

describe('fetchParcelTrades API 어댑터', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('선택한 parcel의 documented trade data를 가져온다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        parcelId: '1001',
        complexId: '501',
        trades: [
          {
            tradeId: '9001',
            dealDate: '2025-12-01',
            exclArea: '84.93',
            dealAmount: '125000',
            aptDong: '101',
            floor: '12',
          },
        ],
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
    });

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/trade/1001'),
      expect.objectContaining({ method: 'GET' }),
    );
  });

  it('valid empty trade list를 empty array로 유지한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({
          parcelId: 1001,
          complexId: null,
          trades: [],
        }),
      ),
    );

    await expect(fetchParcelTrades(1001)).resolves.toEqual({
      parcelId: 1001,
      complexId: null,
      trades: [],
    });
  });

  it('complexId가 있으면 trade URL에 query parameter로 전달한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        parcelId: 1001,
        complexId: 502,
        trades: [],
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchParcelTrades(1001, 502)).resolves.toEqual({
      parcelId: 1001,
      complexId: 502,
      trades: [],
    });

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/trade/1001?complexId=502'),
      expect.objectContaining({ method: 'GET' }),
    );
  });

  it('complexId 단독 trade URL을 호출한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        parcelId: 1001,
        complexId: 502,
        trades: [],
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchComplexTrades(502)).resolves.toEqual({
      parcelId: 1001,
      complexId: 502,
      trades: [],
    });

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/complex/502/trades'),
      expect.objectContaining({ method: 'GET' }),
    );
  });

  it('invalid trade item object를 reject한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({
          parcelId: 1001,
          trades: [null],
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
