import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchComplexSearchResults } from './fetchComplexSearchResults';
import { resolveApiUrl } from '../../map/api/resolveApiUrl';

describe('fetchComplexSearchResults', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('gets documented V1 complex search results with the q query parameter', async () => {
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

  it('does not call the API for an empty query', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchComplexSearchResults('  ')).resolves.toEqual([]);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('rejects invalid V1 search response shapes', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ results: [] })));

    await expect(fetchComplexSearchResults('Sample')).rejects.toThrow(
      'Invalid V1 complex search response: expected an array',
    );
  });

  it('preserves V1 ProblemDetail detail when search fails', async () => {
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
