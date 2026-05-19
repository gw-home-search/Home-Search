import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchComplexMarkers, type ComplexMarkersRequest } from './fetchComplexMarkers';

describe('fetchComplexMarkers', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('posts documented bounds and filters to the V1 complex marker endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    const request: ComplexMarkersRequest = {
      swLat: 37.45,
      swLng: 126.85,
      neLat: 37.7,
      neLng: 127.2,
      pyeongMin: null,
      pyeongMax: null,
      priceEokMin: null,
      priceEokMax: null,
      ageMin: null,
      ageMax: null,
      unitMin: null,
      unitMax: null,
    };

    await fetchComplexMarkers(request);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/map/complexes',
      expect.objectContaining({
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
      }),
    );
  });

  it('normalizes canonical and temporary legacy marker variants to canonical fields', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse([
          {
            parcelId: 1001,
            lat: 37.5123,
            lng: 127.0456,
            latestDealAmount: 125000,
            unitCntSum: 740,
          },
          {
            id: '1002',
            latitude: '37.6',
            longitude: '127.1',
            latestDealAmount: null,
            unitCntSum: '530',
          },
        ]),
      ),
    );

    await expect(
      fetchComplexMarkers({
        swLat: 37.45,
        swLng: 126.85,
        neLat: 37.7,
        neLng: 127.2,
      }),
    ).resolves.toEqual([
      {
        parcelId: 1001,
        lat: 37.5123,
        lng: 127.0456,
        latestDealAmount: 125000,
        unitCntSum: 740,
      },
      {
        parcelId: 1002,
        lat: 37.6,
        lng: 127.1,
        latestDealAmount: null,
        unitCntSum: 530,
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
