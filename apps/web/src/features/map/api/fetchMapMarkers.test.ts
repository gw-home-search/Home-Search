import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchMapMarkers } from './fetchMapMarkers';

describe('fetchMapMarkers', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('uses the complex marker endpoint at detailed map levels', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    await expect(
      fetchMapMarkers({
        bounds: boundsRequest(),
        level: 4,
      }),
    ).resolves.toEqual({ kind: 'complex', markers: [] });

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/map/complexes',
      expect.objectContaining({
        method: 'POST',
        body: expect.stringContaining('"unitMax":null'),
      }),
    );
  });

  it('uses si-do region markers for wide map levels', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    await expect(
      fetchMapMarkers({
        bounds: boundsRequest(),
        level: 10,
      }),
    ).resolves.toEqual({ kind: 'region', level: 'si-do', markers: [] });

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/map/regions',
      expect.objectContaining({
        method: 'POST',
        body: expect.stringContaining('"region":"si-do"'),
      }),
    );
  });

  it('uses si-gun-gu region markers for middle-wide map levels', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    await fetchMapMarkers({
      bounds: boundsRequest(),
      level: 7,
    });

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/map/regions',
      expect.objectContaining({
        body: expect.stringContaining('"region":"si-gun-gu"'),
      }),
    );
  });

  it('uses eup-myeon-dong region markers before detailed complex levels', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    await fetchMapMarkers({
      bounds: boundsRequest(),
      level: 5,
    });

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/map/regions',
      expect.objectContaining({
        body: expect.stringContaining('"region":"eup-myeon-dong"'),
      }),
    );
  });
});

function boundsRequest() {
  return {
    swLat: 37.45,
    swLng: 126.85,
    neLat: 37.7,
    neLng: 127.2,
  };
}

function jsonResponse(body: unknown): Response {
  return {
    ok: true,
    status: 200,
    json: () => Promise.resolve(body),
  } as Response;
}
