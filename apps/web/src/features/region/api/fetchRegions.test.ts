import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchRegionComplexes, fetchRegionDetail, fetchRootRegions } from './fetchRegions';
import { resolveApiUrl } from '../../map/api/resolveApiUrl';

describe('fetchRegions API 어댑터', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('documented root region을 가져온다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse([
        {
          id: '1',
          name: 'Seoul',
        },
      ]),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchRootRegions()).resolves.toEqual([
      {
        id: 1,
        name: 'Seoul',
      },
    ]);

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/region'),
      expect.objectContaining({ method: 'GET' }),
    );
  });

  it('documented region detail과 child region을 가져온다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        id: '1',
        name: 'Seoul',
        latitude: '37.5663',
        longitude: '126.978',
        children: [
          {
            id: '11',
            name: 'Gangnam-gu',
          },
        ],
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchRegionDetail(1)).resolves.toEqual({
      id: 1,
      name: 'Seoul',
      latitude: 37.5663,
      longitude: 126.978,
      children: [
        {
          id: 11,
          name: 'Gangnam-gu',
        },
      ],
    });

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/region/1'),
      expect.objectContaining({ method: 'GET' }),
    );
  });

  it('invalid region detail children shape를 reject한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({
          id: 1,
          name: 'Seoul',
          latitude: 37.5663,
          longitude: 126.978,
          children: null,
        }),
      ),
    );

    await expect(fetchRegionDetail(1)).rejects.toThrow(
      'Invalid public API region detail response: children must be an array',
    );
  });

  it('region detail 실패 시 public API ProblemDetail detail을 보존한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        errorResponse(404, {
          detail: 'Region not found.',
        }),
      ),
    );

    await expect(fetchRegionDetail(1)).rejects.toThrow(
      'Failed to fetch region detail: 404 Region not found.',
    );
  });

  it('regionId 아래 complex page를 가져온다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse([
        {
          complexId: '701',
          complexName: 'Region Complex',
          parcelId: '2001',
          latitude: '37.5123',
          longitude: '127.0456',
          address: 'Region address',
          dongCnt: '8',
          unitCnt: '740',
          useDate: '2018-05-01',
        },
      ]),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchRegionComplexes(11, { limit: 25, offset: 50 })).resolves.toEqual([
      {
        complexId: 701,
        complexName: 'Region Complex',
        parcelId: 2001,
        latitude: 37.5123,
        longitude: 127.0456,
        address: 'Region address',
        dongCnt: 8,
        unitCnt: 740,
        useDate: '2018-05-01',
      },
    ]);

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/region/11/complexes?limit=25&offset=50'),
      expect.objectContaining({ method: 'GET' }),
    );
  });

  it('region complex page의 null address를 보존한다', async () => {
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

    await expect(fetchRegionComplexes(11)).resolves.toEqual([
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

function errorResponse(status: number, body: unknown): Response {
  return {
    ok: false,
    status,
    json: () => Promise.resolve(body),
  } as Response;
}
