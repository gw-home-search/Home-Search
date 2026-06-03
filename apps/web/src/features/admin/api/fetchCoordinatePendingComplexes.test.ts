import { describe, expect, it, vi } from 'vitest';

import {
  approveCoordinateOverride,
  fetchCoordinatePendingComplexes,
} from './fetchCoordinatePendingComplexes';
import { resolveApiUrl } from '../../map/api/resolveApiUrl';

describe('coordinate override admin API client 계약', () => {
  it('coordinate-pending 목록을 documented admin endpoint에서 조회한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([
      {
        parcelId: 1001,
        complexId: 501,
        pnu: '1168010300101400001',
        aptSeq: 'APT-501',
        aptName: 'Pending Apartment',
        address: 'Pending address',
        tradeCount: 3,
        createdAt: '2026-06-03T00:00:00Z',
      },
    ]));
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchCoordinatePendingComplexes(25)).resolves.toEqual([
      {
        parcelId: 1001,
        complexId: 501,
        pnu: '1168010300101400001',
        aptSeq: 'APT-501',
        aptName: 'Pending Apartment',
        address: 'Pending address',
        tradeCount: 3,
        createdAt: '2026-06-03T00:00:00Z',
      },
    ]);
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/admin/coordinates/pending?limit=25'),
      expect.objectContaining({ method: 'GET' }),
    );

    vi.unstubAllGlobals();
  });

  it('approved override를 documented admin endpoint에 PUT한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      pnu: '1168010300101400001',
      latitude: 37.5123,
      longitude: 127.0456,
      parcelUpdated: true,
    }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(approveCoordinateOverride('1168010300101400001', {
      latitude: 37.5123,
      longitude: 127.0456,
      reason: 'operator verified missing coordinate',
      approvedBy: 'test-operator',
    })).resolves.toEqual({
      pnu: '1168010300101400001',
      latitude: 37.5123,
      longitude: 127.0456,
      parcelUpdated: true,
    });
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/admin/coordinates/1168010300101400001/override'),
      expect.objectContaining({
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          latitude: 37.5123,
          longitude: 127.0456,
          reason: 'operator verified missing coordinate',
          approvedBy: 'test-operator',
        }),
      }),
    );

    vi.unstubAllGlobals();
  });
});

function jsonResponse(payload: unknown): Response {
  return new Response(JSON.stringify(payload), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}
