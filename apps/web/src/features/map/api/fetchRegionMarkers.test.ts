import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchRegionMarkers, type RegionMarkersRequest } from './fetchRegionMarkers';

describe('fetchRegionMarkers', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('posts documented bounds and region level to the V1 region marker endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    const request: RegionMarkersRequest = {
      swLat: 37.45,
      swLng: 126.85,
      neLat: 37.7,
      neLng: 127.2,
      region: 'si-gun-gu',
    };

    await fetchRegionMarkers(request);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/map/regions',
      expect.objectContaining({
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
      }),
    );
  });

  it('normalizes canonical region marker response without leaking optional trend', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse([
          {
            id: 1,
            name: 'Seoul',
            lat: 37.5663,
            lng: 126.978,
            trend: null,
          },
          {
            id: 2,
            name: 'Gangnam-gu',
            lat: 37.5172,
            lng: 127.0473,
            trend: 3.2,
          },
        ]),
      ),
    );

    await expect(
      fetchRegionMarkers({
        swLat: 37.45,
        swLng: 126.85,
        neLat: 37.7,
        neLng: 127.2,
        region: 'si-gun-gu',
      }),
    ).resolves.toEqual([
      {
        id: 1,
        name: 'Seoul',
        lat: 37.5663,
        lng: 126.978,
      },
      {
        id: 2,
        name: 'Gangnam-gu',
        lat: 37.5172,
        lng: 127.0473,
      },
    ]);
  });

  it('keeps temporary source field variants inside the adapter', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse([
          {
            id: '11',
            regionName: 'Mapo-gu',
            latitude: '37.5662',
            longitude: '126.9016',
            trend: 1.1,
          },
        ]),
      ),
    );

    await expect(
      fetchRegionMarkers({
        swLat: 37.45,
        swLng: 126.85,
        neLat: 37.7,
        neLng: 127.2,
        region: 'si-gun-gu',
      }),
    ).resolves.toEqual([
      {
        id: 11,
        name: 'Mapo-gu',
        lat: 37.5662,
        lng: 126.9016,
      },
    ]);
  });

  it('returns an empty marker list for a valid empty response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([])));

    await expect(
      fetchRegionMarkers({
        swLat: 37.45,
        swLng: 126.85,
        neLat: 37.7,
        neLng: 127.2,
        region: 'eup-myeon-dong',
      }),
    ).resolves.toEqual([]);
  });

  it('throws a clear contract error when the response is not an array', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ markers: [] })));

    await expect(
      fetchRegionMarkers({
        swLat: 37.45,
        swLng: 126.85,
        neLat: 37.7,
        neLng: 127.2,
        region: 'si-gun-gu',
      }),
    ).rejects.toThrow('Invalid V1 region marker response: expected an array');
  });

  it('throws a clear contract error when a marker has an invalid coordinate', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse([
          {
            id: 1,
            name: 'Seoul',
            lat: null,
            lng: 126.978,
          },
        ]),
      ),
    );

    await expect(
      fetchRegionMarkers({
        swLat: 37.45,
        swLng: 126.85,
        neLat: 37.7,
        neLng: 127.2,
        region: 'si-gun-gu',
      }),
    ).rejects.toThrow('Invalid V1 region marker response: lat must be a number');
  });

  it('rejects with a clear marker fetch error when the response is not ok', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(errorResponse(500)));

    await expect(
      fetchRegionMarkers({
        swLat: 37.45,
        swLng: 126.85,
        neLat: 37.7,
        neLng: 127.2,
        region: 'si-do',
      }),
    ).rejects.toThrow('Failed to fetch region markers: 500');
  });

  it('preserves V1 ProblemDetail detail when the region endpoint rejects the request', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        errorResponse(400, {
          type: '/docs/index.html#error-code-list',
          title: 'C401',
          status: 400,
          detail: 'Unsupported region.',
          exception: 'MapApiException',
          timestamp: '2026-05-18T10:30:00',
        }),
      ),
    );

    await expect(
      fetchRegionMarkers({
        swLat: 37.45,
        swLng: 126.85,
        neLat: 37.7,
        neLng: 127.2,
        region: 'si-do',
      }),
    ).rejects.toThrow('Failed to fetch region markers: 400 Unsupported region.');
  });
});

function jsonResponse(body: unknown): Response {
  return {
    ok: true,
    status: 200,
    json: () => Promise.resolve(body),
  } as Response;
}

function errorResponse(status: number, body?: unknown): Response {
  const response: Partial<Response> = {
    ok: false,
    status,
  };

  if (body !== undefined) {
    response.json = () => Promise.resolve(body);
  }

  return response as Response;
}
