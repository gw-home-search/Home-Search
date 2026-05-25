import { afterEach, describe, expect, it, vi } from 'vitest';

import { resolveApiUrl } from './resolveApiUrl';

describe('resolveApiUrl helper 동작', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('VITE_API_SERVER_IP가 없으면 local API runtime base를 사용한다', () => {
    vi.stubEnv('VITE_API_SERVER_IP', '');

    expect(resolveApiUrl('/api/v1/map/complexes')).toBe(
      'http://localhost:8080/api/v1/map/complexes',
    );
  });

  it('public API path에 configured VITE_API_SERVER_IP를 보존한다', () => {
    vi.stubEnv('VITE_API_SERVER_IP', 'http://127.0.0.1:18080/');

    expect(resolveApiUrl('/api/v1/search/complexes?q=Sample')).toBe(
      'http://127.0.0.1:18080/api/v1/search/complexes?q=Sample',
    );
  });

  it('local shell export의 host-only VITE_API_SERVER_IP 값을 normalize한다', () => {
    vi.stubEnv('VITE_API_SERVER_IP', '127.0.0.1:18080');

    expect(resolveApiUrl('/api/v1/detail/1001')).toBe(
      'http://127.0.0.1:18080/api/v1/detail/1001',
    );
  });
});
