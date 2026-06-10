import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchComplexSuggestions } from './fetchComplexSuggestions';
import { resolveApiUrl } from '../../map/api/resolveApiUrl';

describe('fetchComplexSuggestions API 어댑터', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('autocomplete suggestion 목록을 가져온다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse([
        {
          complexId: '501',
          complexName: 'Sample Apartment',
          parcelId: '1001',
          address: 'Sample address',
        },
      ]),
    );
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchComplexSuggestions('  Sample  ')).resolves.toEqual([
      {
        complexId: 501,
        complexName: 'Sample Apartment',
        parcelId: 1001,
        address: 'Sample address',
      },
    ]);

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/search/complexes/suggestions?q=Sample'),
      expect.objectContaining({ method: 'GET' }),
    );
  });

  it('blank query에서는 API를 호출하지 않는다', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchComplexSuggestions(' ')).resolves.toEqual([]);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('주소가 없는 suggestion의 null address를 보존한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse([
          {
            complexId: 4368,
            complexName: '힐스테이트세운센트럴1단지',
            parcelId: 4669,
            address: null,
          },
        ]),
      ),
    );

    await expect(fetchComplexSuggestions('힐스테이트세운센트럴')).resolves.toEqual([
      {
        complexId: 4368,
        complexName: '힐스테이트세운센트럴1단지',
        parcelId: 4669,
        address: null,
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
