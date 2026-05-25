import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchComplexMarkers, type ComplexMarkersRequest } from './fetchComplexMarkers';
import { resolveApiUrl } from './resolveApiUrl';

describe('fetchComplexMarkers API 어댑터', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('documented bounds와 filter를 V1 complex marker endpoint에 post한다', async () => {
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
      resolveApiUrl('/api/v1/map/complexes'),
      expect.objectContaining({
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
      }),
    );
  });

  it('canonical/temporary legacy marker variant를 canonical field로 normalize한다', async () => {
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

  it('valid empty response에서 empty marker list를 반환한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([])));

    await expect(
      fetchComplexMarkers({
        swLat: 37.45,
        swLng: 126.85,
        neLat: 37.7,
        neLng: 127.2,
      }),
    ).resolves.toEqual([]);
  });

  it('response가 array가 아니면 clear contract error를 throw한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ markers: [] })));

    await expect(
      fetchComplexMarkers({
        swLat: 37.45,
        swLng: 126.85,
        neLat: 37.7,
        neLng: 127.2,
      }),
    ).rejects.toThrow('Invalid V1 complex marker response: expected an array');
  });

  it('marker에 unit count가 없으면 clear contract error를 throw한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse([
          {
            parcelId: 1001,
            lat: 37.5123,
            lng: 127.0456,
            latestDealAmount: 125000,
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
    ).rejects.toThrow('Invalid V1 complex marker response: unitCntSum must be a number');
  });

  it('marker endpoint가 request를 reject하면 V1 ProblemDetail detail을 보존한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        errorResponse(400, {
          type: '/docs/index.html#error-code-list',
          title: 'C401',
          status: 400,
          detail: 'Invalid parameter format.',
          exception: 'MapApiException',
          timestamp: '2026-05-18T10:30:00',
        }),
      ),
    );

    await expect(
      fetchComplexMarkers({
        swLat: 37.45,
        swLng: 126.85,
        neLat: 37.7,
        neLng: 127.2,
      }),
    ).rejects.toThrow('Failed to fetch complex markers: 400 Invalid parameter format.');
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
