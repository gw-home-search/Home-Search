import { afterEach, expect, it, vi } from 'vitest';
import { adminRequest } from './adminHttp';
afterEach(() => vi.unstubAllGlobals());
it('mutation에 same-origin credential과 CSRF header를 첨부한다', async () => {
  Object.defineProperty(document, 'cookie', { configurable: true, value: 'XSRF-TOKEN=test-token' });
  const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 })); vi.stubGlobal('fetch', fetchMock);
  await adminRequest('/api/test', { method: 'POST' });
  const options = fetchMock.mock.calls[0][1]; expect(options.credentials).toBe('same-origin'); expect((options.headers as Headers).get('X-XSRF-TOKEN')).toBe('test-token');
});

it('401 응답은 session expired event와 안전한 오류를 제공한다', async () => {
  const listener = vi.fn(); window.addEventListener('admin-session-expired', listener);
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })));
  await expect(adminRequest('/api/test')).rejects.toMatchObject({ status: 401, message: '세션이 만료되었습니다.' });
  expect(listener).toHaveBeenCalledOnce();
  window.removeEventListener('admin-session-expired', listener);
});
