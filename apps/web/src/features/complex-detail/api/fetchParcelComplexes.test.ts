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

  it('주소가 없는 selectable complex의 null address를 보존한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse([
          {
            complexId: 4368,
            complexName: '힐스테이트세운센트럴1단지',
            parcelId: 4669,
            latitude: 37.567994,
            longitude: 126.9930772,
            address: null,
            dongCnt: null,
            unitCnt: null,
            useDate: null,
          },
        ]),
      ),
    );

    await expect(fetchParcelComplexes(4669)).resolves.toEqual([
      {
        complexId: 4368,
        complexName: '힐스테이트세운센트럴1단지',
        parcelId: 4669,
        latitude: 37.567994,
        longitude: 126.9930772,
        address: null,
        dongCnt: null,
        unitCnt: null,
        useDate: null,
      },
    ]);
  });
});

function jsonResponse(body: unknown): Response {
  return {
    ok: true,
    status: 200,
    json: () => Promise.resolve(body),
  } as Response;
}
