import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchParcelComplexes } from './fetchParcelComplexes';
import { resolveApiUrl } from '../../map/api/resolveApiUrl';

describe('fetchParcelComplexes API 어댑터', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('parcelId 아래 selectable complex 목록을 가져온다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse([
        {
          complexId: '501',
          complexName: 'Tower A',
          parcelId: '1001',
          latitude: '37.5123',
          longitude: '127.0456',
          address: 'Sample address',
          dongCnt: '5',
          unitCnt: '320',
          useDate: '2015-03-20',
        },
      ]),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchParcelComplexes(1001)).resolves.toEqual([
      {
        complexId: 501,
        complexName: 'Tower A',
        parcelId: 1001,
        latitude: 37.5123,
        longitude: 127.0456,
        address: 'Sample address',
        dongCnt: 5,
        unitCnt: 320,
        useDate: '2015-03-20',
      },
    ]);

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/detail/1001/complexes'),
      expect.objectContaining({ method: 'GET' }),
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
