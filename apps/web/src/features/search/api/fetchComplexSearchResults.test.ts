import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchComplexSearchResults } from './fetchComplexSearchResults';
import { resolveApiUrl } from '../../map/api/resolveApiUrl';

describe('fetchComplexSearchResults API 어댑터', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('q query parameter로 documented complex search result를 가져온다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse([
        {
          complexId: '501',
          complexName: 'Sample Apartment',
          parcelId: '1001',
          latitude: '37.5123',
          longitude: '127.0456',
          address: 'Sample address',
        },
      ]),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchComplexSearchResults('  Sample Apartment  ')).resolves.toEqual([
      {
        complexId: 501,
        complexName: 'Sample Apartment',
        parcelId: 1001,
        latitude: 37.5123,
        longitude: 127.0456,
        address: 'Sample address',
      },
    ]);

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/search/complexes?q=Sample+Apartment'),
      expect.objectContaining({ method: 'GET' }),
    );
  });

  it('empty query에서는 API를 호출하지 않는다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchComplexSearchResults('  ')).resolves.toEqual([]);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('coordinate-pending search result의 null 좌표를 보존한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse([
          {
            complexId: 801,
            complexName: 'Coordinate Pending Complex',
            parcelId: 3001,
            latitude: null,
            longitude: null,
            address: 'Coordinate pending address',
          },
        ]),
      ),
    );

    await expect(fetchComplexSearchResults('pending')).resolves.toEqual([
      {
        complexId: 801,
        complexName: 'Coordinate Pending Complex',
        parcelId: 3001,
        latitude: null,
        longitude: null,
        address: 'Coordinate pending address',
      },
    ]);
  });

  it('주소가 없는 search result의 null address를 보존한다', async () => {
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
          },
        ]),
      ),
    );

    await expect(fetchComplexSearchResults('힐스테이트세운센트럴')).resolves.toEqual([
      {
        complexId: 4368,
        complexName: '힐스테이트세운센트럴1단지',
        parcelId: 4669,
        latitude: 37.567994,
        longitude: 126.9930772,
        address: null,
      },
    ]);
  });

  it('invalid search response shape를 reject한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ results: [] })));

    await expect(fetchComplexSearchResults('Sample')).rejects.toThrow(
      'Invalid public API complex search response: expected an array',
    );
  });

  it('search 실패 시 public API ProblemDetail detail을 보존한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        errorResponse(400, {
          detail: 'Invalid query parameter.',
        }),
      ),
    );

    await expect(fetchComplexSearchResults('Sample')).rejects.toThrow(
      'Failed to fetch complex search results: 400 Invalid query parameter.',
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
