import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchWithTimeout } from './fetchWithTimeout';

describe('fetchWithTimeout', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('응답이 제한시간 안에 끝나지 않으면 TimeoutError를 반환한다', async () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => undefined)));

    await expect(fetchWithTimeout('/slow', {}, 5)).rejects.toMatchObject({
      name: 'TimeoutError',
    });
  });

  it('caller abort는 timeout과 구분된 AbortError이며 reason을 전달하지 않는다', async () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => undefined)));
    const controller = new AbortController();
    const request = fetchWithTimeout('/cancelled', { signal: controller.signal }, 1_000);

    controller.abort(new Error('private abort reason'));

    await expect(request).rejects.toMatchObject({ name: 'AbortError' });
    await expect(request).rejects.not.toThrow('private abort reason');
  });

  it('정상 응답은 timeout controller signal을 연결해 그대로 반환한다', async () => {
    const response = new Response('[]', { status: 200 });
    const fetchMock = vi.fn().mockResolvedValue(response);
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchWithTimeout('/ready', { method: 'GET' }, 50)).resolves.toBe(response);
    expect(fetchMock).toHaveBeenCalledWith('/ready', expect.objectContaining({
      method: 'GET',
      signal: expect.any(AbortSignal),
    }));
  });
});
