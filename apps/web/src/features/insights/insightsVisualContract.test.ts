import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const css = readFileSync(`${process.cwd()}/src/features/insights/insights.css`, 'utf8');

describe('지도 인사이트 visual contract', () => {
  it('기존 semantic token만 사용하고 과장된 card 효과를 만들지 않는다', () => {
    expect(css).not.toMatch(/#[0-9a-f]{3,8}\b/i);
    expect(css).not.toMatch(/(?:linear|radial|conic)-gradient\s*\(/i);
    expect(css).not.toMatch(/backdrop-filter\s*:/i);
    expect(css).not.toMatch(/transition\s*:\s*all\b/i);
    expect(css).not.toMatch(/\.insight-trade-row[^}]*box-shadow\s*:/is);
    expect(css).not.toMatch(/\.insight-(?:card|card-grid|hero)\b/i);
  });

  it('모바일 지역 변경과 복구 action은 44px 조작 영역을 유지한다', () => {
    expect(css).toMatch(/@media\s*\(max-width:\s*720px\)[\s\S]*\.insight-context-region button\s*\{[^}]*min-height:\s*44px/i);
    expect(css).toMatch(/@media\s*\(max-width:\s*720px\)[\s\S]*\.insight-state-copy button\s*\{[^}]*min-height:\s*44px/i);
  });

  it('지역과 취소를 제외한 5개 metric을 좁은 rail 안에서 모두 노출한다', () => {
    expect(css).toMatch(/\.map-mode-navigation\s*\{[^}]*grid-template-columns:\s*repeat\(6,\s*minmax\(48px,\s*1fr\)\)/is);
    expect(css).toMatch(/\.map-mode-navigation a\s*\{[^}]*min-width:\s*48px/is);
  });
});
