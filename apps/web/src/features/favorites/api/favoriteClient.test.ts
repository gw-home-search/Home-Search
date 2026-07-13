import { describe, expect, it, vi } from 'vitest';

import { createFavoriteClient, FavoriteClientError } from './favoriteClient';

describe('favoriteClient', () => {
  it('단건/list 응답을 검증하고 PUT/DELETE는 body 없이 호출한다', async () => {
    const request = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ complexId: 501, favorite: true, savedAt: '2026-07-13T06:00:00Z' }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    const client = createFavoriteClient(request);

    await expect(client.get(501)).resolves.toEqual({ complexId: 501, favorite: true, savedAt: '2026-07-13T06:00:00Z' });
    await client.save(501);
    await client.remove(501);
    expect(request).toHaveBeenNthCalledWith(2, '/api/v1/favorites/501', expect.objectContaining({ method: 'PUT' }));
    expect(request.mock.calls[1]?.[1]).not.toHaveProperty('body');
    expect(request).toHaveBeenNthCalledWith(3, '/api/v1/favorites/501', expect.objectContaining({ method: 'DELETE' }));
  });

  it('한도 code와 invalid response를 generic 원인 노출 없이 구분한다', async () => {
    const limit = createFavoriteClient(vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ code: 'FAVORITE_LIMIT_REACHED', detail: 'raw backend reason' }),
      { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
    )));
    await expect(limit.save(501)).rejects.toMatchObject({ kind: 'limit' });

    const invalid = createFavoriteClient(vi.fn().mockResolvedValue(jsonResponse({ favorite: 'yes' })));
    await expect(invalid.get(501)).rejects.toBeInstanceOf(FavoriteClientError);
    await expect(invalid.get(501)).rejects.not.toThrow('raw backend reason');
  });
});

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
}
