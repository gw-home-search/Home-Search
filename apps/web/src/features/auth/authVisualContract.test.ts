import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const authCss = readFileSync(`${process.cwd()}/src/features/auth/auth.css`, 'utf8');
const designSystem = readFileSync(`${process.cwd()}/src/app/design-system.css`, 'utf8');

describe('auth 반응형 디자인 계약', () => {
  it('provider raw color는 design token에만 선언한다', () => {
    expect(designSystem).toContain('--hs-auth-provider-kakao: #fee500;');
    expect(designSystem).toContain('--hs-auth-provider-naver: #03a94d;');
    expect(designSystem).toContain('--hs-auth-provider-google-surface: #ffffff;');
    expect(designSystem).toContain('--hs-auth-provider-google-border: #747775;');
    expect(designSystem).toContain('--hs-auth-google-blue: #4285f4;');
    expect(authCss).not.toMatch(/#fee500|#03a94d|#747775|#4285f4/i);
  });

  it('provider button은 이전 plain-text markup에서도 세로로 깨지지 않는 중앙 flex를 사용한다', () => {
    expect(authCss).toContain('.auth-provider-button { display: flex;');
    expect(authCss).toContain('justify-content: center;');
    expect(authCss).toContain('gap: 8px;');
    expect(authCss).toContain('white-space: nowrap;');
    expect(authCss).not.toContain('grid-template-columns: 24px minmax(0, 1fr) 24px;');
    expect(authCss).not.toContain('.auth-provider-balance');
    expect(authCss).toContain('border-radius: 12px;');
    expect(authCss).toContain('.auth-provider-icon { display: block; width: 20px; height: 20px;');
    expect(authCss).toContain('.auth-provider-google { border: 1px solid var(--hs-auth-provider-google-border);');
    expect(authCss).toContain('.account-login-icon { width: 16px; height: 16px;');
  });

  it('desktop dialog와 mobile bottom sheet 크기·safe area를 고정한다', () => {
    expect(authCss).toContain('width: min(440px, calc(100vw - 32px));');
    expect(authCss).toContain('@media (max-width: 720px)');
    expect(authCss).toContain('max-height: 80dvh;');
    expect(authCss).toContain('margin: auto 0 0;');
    expect(authCss).toContain('padding: 20px 16px max(20px, env(safe-area-inset-bottom));');
    expect(authCss).toContain('@media (prefers-reduced-motion: reduce)');
  });

  it('authenticated account toggle은 상시 box 대신 가벼운 header action으로 표시한다', () => {
    const accountChipRule = authCss.match(/\.account-chip \{([^}]*)\}/)?.[1] ?? '';
    expect(accountChipRule).toContain('border: 0;');
    expect(accountChipRule).toContain('background: transparent;');
    expect(accountChipRule).toContain('width: max-content;');
    expect(accountChipRule).toContain('max-width: min(172px, 32vw);');
    expect(accountChipRule).toContain('grid-template-columns: 32px minmax(0, max-content) 18px;');
    expect(accountChipRule).toContain('justify-content: end;');
    expect(accountChipRule).not.toContain('minmax(0, 1fr)');
    expect(authCss).toContain('.account-chip[aria-expanded="true"]');
    expect(authCss).toContain('.account-chip[aria-expanded="true"] .account-chip-chevron');
    expect(authCss).toContain('transform: rotate(180deg);');
  });
});
