import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchComplexDetail } from './fetchComplexDetail';
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
      name: 'Sample complex name',
      dongCnt: 8,
      unitCnt: 740,
      platArea: 12345.67,
      archArea: 2345.67,
      totArea: 98765.43,
      bcRat: 22.5,
      vlRat: 199.8,
      useDate: '2015-03-20',
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
