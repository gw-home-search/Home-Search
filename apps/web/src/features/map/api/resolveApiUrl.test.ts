import { afterEach, describe, expect, it, vi } from 'vitest';

import { resolveApiUrl } from './resolveApiUrl';

describe('resolveApiUrl', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('uses the local API runtime base when VITE_API_SERVER_IP is not configured', () => {
    vi.stubEnv('VITE_API_SERVER_IP', '');

    expect(resolveApiUrl('/api/v1/map/complexes')).toBe(
      'http://localhost:8080/api/v1/map/complexes',
    );
  });

  it('preserves the configured VITE_API_SERVER_IP for V1 API paths', () => {
    vi.stubEnv('VITE_API_SERVER_IP', 'http://127.0.0.1:18080/');

    expect(resolveApiUrl('/api/v1/search/complexes?q=Sample')).toBe(
      'http://127.0.0.1:18080/api/v1/search/complexes?q=Sample',
    );
  });

  it('normalizes host-only VITE_API_SERVER_IP values from local shell exports', () => {
    vi.stubEnv('VITE_API_SERVER_IP', '127.0.0.1:18080');

    expect(resolveApiUrl('/api/v1/detail/1001')).toBe(
      'http://127.0.0.1:18080/api/v1/detail/1001',
    );
  });
});
