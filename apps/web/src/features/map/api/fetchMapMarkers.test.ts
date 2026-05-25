import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchMapMarkers } from './fetchMapMarkers';
import { resolveApiUrl } from './resolveApiUrl';

describe('fetchMapMarkers API 어댑터', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('detailed map level에서 complex marker endpoint를 사용한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    await expect(
      fetchMapMarkers({
        bounds: boundsRequest(),
        level: 4,
      }),
    ).resolves.toEqual({ kind: 'complex', markers: [] });

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/map/complexes'),
      expect.objectContaining({
        method: 'POST',
        body: expect.stringContaining('"unitMax":null'),
      }),
    );
  });

  it('wide map level에서 si-do region marker를 사용한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    await expect(
      fetchMapMarkers({
        bounds: boundsRequest(),
        level: 10,
      }),
    ).resolves.toEqual({ kind: 'region', level: 'si-do', markers: [] });

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/map/regions'),
      expect.objectContaining({
        method: 'POST',
        body: expect.stringContaining('"region":"si-do"'),
      }),
    );
  });

  it('middle-wide map level에서 si-gun-gu region marker를 사용한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    await fetchMapMarkers({
      bounds: boundsRequest(),
      level: 7,
    });

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/map/regions'),
      expect.objectContaining({
        body: expect.stringContaining('"region":"si-gun-gu"'),
      }),
    );
  });

  it('detailed complex level 전에는 eup-myeon-dong region marker를 사용한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    await fetchMapMarkers({
      bounds: boundsRequest(),
      level: 5,
    });

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/map/regions'),
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
