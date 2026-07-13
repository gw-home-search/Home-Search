import { describe, expect, it } from 'vitest';

import { resolveUserApiUrl } from './resolveUserApiUrl';

describe('resolveUserApiUrl 사용자 API 주소', () => {
  it('property-data 설정과 분리된 user-service base를 정규화한다', () => {
    expect(resolveUserApiUrl('https://user.example.com/', 'production')).toBe('https://user.example.com');
  });

  it('local/test에서만 localhost fallback을 허용한다', () => {
    expect(resolveUserApiUrl(undefined, 'test')).toBe('http://localhost:8082');
    expect(resolveUserApiUrl('', 'development')).toBe('http://localhost:8082');
    expect(() => resolveUserApiUrl(undefined, 'production')).toThrow('VITE_USER_API_SERVER_IP');
  });
});
