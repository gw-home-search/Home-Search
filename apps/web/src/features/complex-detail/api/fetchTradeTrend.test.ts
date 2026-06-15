import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchComplexTradeTrend, fetchParcelTradeTrend } from './fetchTradeTrend';
import { resolveApiUrl } from '../../map/api/resolveApiUrl';

describe('fetchTradeTrend API 어댑터', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('parcel 월별 추세 배열을 파싱한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse([
        { month: '2025-10', avgAmount: '100000', count: '1', minAmount: '100000', maxAmount: '100000' },
        { month: '2025-12', avgAmount: 127500, count: 2, minAmount: 125000, maxAmount: 130000 },
      ]),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchParcelTradeTrend(1001)).resolves.toEqual([
      { month: '2025-10', avgAmount: 100000, count: 1, minAmount: 100000, maxAmount: 100000 },
      { month: '2025-12', avgAmount: 127500, count: 2, minAmount: 125000, maxAmount: 130000 },
    ]);

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/trade/1001/trend'),
      expect.objectContaining({ method: 'GET' }),
    );
  });

  it('complexId가 있으면 trend URL에 query parameter로 전달한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchParcelTradeTrend(1001, 502)).resolves.toEqual([]);

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/trade/1001/trend?complexId=502'),
      expect.objectContaining({ method: 'GET' }),
    );
  });

  it('complexId 단독 trade-trend URL을 호출한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchComplexTradeTrend(502)).resolves.toEqual([]);

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/complex/502/trade-trend'),
      expect.objectContaining({ method: 'GET' }),
    );
  });

  it('배열이 아닌 응답을 reject한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ month: '2025-12' })));

    await expect(fetchParcelTradeTrend(1001)).rejects.toThrow(
      'Invalid public API trade trend response: expected an array',
    );
  });

  it('trend lookup 실패 시 public API ProblemDetail detail로 reject한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(errorResponse(404, { detail: 'Parcel not found.' })),
    );

    await expect(fetchParcelTradeTrend(1001)).rejects.toThrow(
      'Failed to fetch trade trend: 404 Parcel not found.',
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
