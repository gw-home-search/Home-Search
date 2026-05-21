import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchParcelTrades } from './fetchParcelTrades';
import { resolveApiUrl } from '../../map/api/resolveApiUrl';

describe('fetchParcelTrades', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('gets documented V1 trade data for the selected parcel', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        parcelId: '1001',
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

  it('keeps a valid empty V1 trade list as an empty array', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({
          parcelId: 1001,
          trades: [],
        }),
      ),
    );

    await expect(fetchParcelTrades(1001)).resolves.toEqual({
      parcelId: 1001,
      trades: [],
    });
  });

  it('rejects invalid V1 trade item objects', async () => {
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
      'Invalid V1 parcel trade response: trade item must be an object',
    );
  });

  it('rejects with V1 ProblemDetail detail when trade lookup fails', async () => {
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
