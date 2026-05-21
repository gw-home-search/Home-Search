import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchRegionDetail, fetchRootRegions } from './fetchRegions';
import { resolveApiUrl } from '../../map/api/resolveApiUrl';

describe('fetchRegions', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('gets documented V1 root regions', async () => {
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

  it('gets documented V1 region detail and child regions', async () => {
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

  it('rejects invalid V1 region detail children shapes', async () => {
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
      'Invalid V1 region detail response: children must be an array',
    );
  });

  it('preserves V1 ProblemDetail detail when region detail fails', async () => {
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
