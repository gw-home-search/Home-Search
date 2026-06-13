import { describe, expect, it, vi } from 'vitest';
import { fetchMetadataPending, retryMetadata } from './metadataAdminApi';
import { resolveApiUrl } from '../../map/api/resolveApiUrl';

describe('metadata admin API client 계약', () => {
  it('pending 조회와 retry 요청에 관리자 접근 코드와 canonical route를 사용한다', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }))
      .mockResolvedValueOnce(new Response('{"updated":true}', { status: 200, headers: { 'Content-Type': 'application/json' } }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchMetadataPending('test-admin')).resolves.toEqual([]);
    await retryMetadata(501, { actor: 'operator', reason: 'source updated' }, 'test-admin');

    expect(fetchMock).toHaveBeenNthCalledWith(1, resolveApiUrl('/api/v1/admin/metadata/pending?limit=50&offset=0'),
      expect.objectContaining({ headers: { 'X-Admin-Access-Code': 'test-admin' } }));
    expect(fetchMock).toHaveBeenNthCalledWith(2, resolveApiUrl('/api/v1/admin/metadata/501/retry'),
      expect.objectContaining({
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Admin-Access-Code': 'test-admin' },
        body: JSON.stringify({ actor: 'operator', reason: 'source updated' }),
      }));
    vi.unstubAllGlobals();
  });
});
