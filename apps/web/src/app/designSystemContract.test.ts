import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const appDirectory = `${process.cwd()}/src/app`;
const designSystem = readFileSync(`${appDirectory}/design-system.css`, 'utf8');
const shell = readFileSync(`${appDirectory}/styles/app-shell.css`, 'utf8');
const detail = readFileSync(`${appDirectory}/styles/detail.css`, 'utf8');
const exploration = readFileSync(`${appDirectory}/styles/exploration.css`, 'utf8');
const map = readFileSync(`${appDirectory}/styles/map.css`, 'utf8');
const responsive = readFileSync(`${appDirectory}/styles/responsive.css`, 'utf8');
const icons = readFileSync(`${process.cwd()}/src/shared/icons/index.tsx`, 'utf8');
const mapApp = readFileSync(`${appDirectory}/MapApp.tsx`, 'utf8');
const tradeTrendChart = readFileSync(`${process.cwd()}/src/features/complex-detail/TradeTrendChart.tsx`, 'utf8');
const detailSidebar = readFileSync(`${process.cwd()}/src/features/complex-detail/DetailSidebar.tsx`, 'utf8');
const indexHtml = readFileSync(`${process.cwd()}/index.html`, 'utf8');

describe('공개 지도 디자인 시스템 계약', () => {
  it('public map palette와 layout semantic token을 고정한다', () => {
    expect(designSystem).toContain('--hs-map-color-primary: #0ea5b7;');
    expect(designSystem).toContain('--hs-map-color-brand: #0e7490;');
    expect(designSystem).toContain('--hs-map-color-brand-soft: #ecf7f8;');
    expect(designSystem).toContain('--hs-map-color-action: #0e7490;');
    expect(designSystem).toContain('--hs-map-color-ink: #172033;');
    expect(designSystem).toContain('--hs-map-color-line: #dce4e8;');
    expect(designSystem).toContain('--hs-map-color-surface-muted: #f6f8f9;');
    expect(designSystem).toContain('--hs-map-color-trend: #0e7490;');
    expect(designSystem).toContain('--hs-map-rail-width-wide: 380px;');
    expect(designSystem).toContain('--hs-map-rail-width-compact: 352px;');
    expect(designSystem).toContain('--hs-map-shell-bar-height: 56px;');
    expect(designSystem).toContain('--hs-map-mobile-brand-height: 58px;');
    expect(designSystem).toContain('--hs-map-filter-height: 62px;');
    expect(designSystem).toContain('--hs-map-radius-marker: 6px;');
    expect(designSystem).toContain('--hs-map-mobile-min-height: 180px;');
    expect(designSystem).toContain('--hs-map-z-filter-popover: 60;');
    expect(designSystem).toContain('--hs-map-type-panel-title-size: 15px;');
    expect(designSystem).toContain('--hs-map-type-panel-title-line: 20px;');
    expect(designSystem).toContain('--hs-map-type-row-title-size: 13px;');
    expect(designSystem).toContain('--hs-map-type-row-meta-size: 11px;');
  });

  it('거래 추세 차트 fallback을 public map trend color와 일치시킨다', () => {
    expect(tradeTrendChart).toContain("const TREND_LINE_FALLBACK = '#0e7490';");
    expect(tradeTrendChart).not.toContain('rgb(189 87 39)');
  });

  it('모바일의 숨은 시세 tab에서는 폭 0 차트를 미리 그리지 않는다', () => {
    expect(detailSidebar).toContain("useMediaQuery('(max-width: 720px)')");
    expect(detailSidebar).toContain('{shouldRenderTradeChart ? (');
    expect(tradeTrendChart).toContain('<ResponsiveContainer width="100%" height="100%" minWidth={0}>');
  });

  it('기본 marker는 soft surface와 accent rail을 쓰고 선택 상태만 진한 action color를 쓴다', () => {
    expect(designSystem).toContain('--hs-map-color-marker-soft: #dceff0;');
    expect(designSystem).toContain('--hs-map-color-marker-accent: #62aab2;');
    expect(map).toContain('border-left: 3px solid var(--hs-map-color-marker-accent);');
    expect(map).toContain('.map-marker-complex[data-state="selected"] {');
    expect(map).toContain('border-left-width: 3px;');
    expect(map).toContain('.map-marker-complex[data-state="idle"] .map-marker-kicker { display: none; }');
    expect(map).toContain('background: var(--hs-map-color-marker-soft);');
    expect(map).toContain('color: var(--hs-map-color-action);');
    expect(map).not.toContain('background: var(--hs-map-color-region); color: var(--hs-map-color-surface);');
  });

  it('기존 shared token을 유지하고 public stylesheet에는 raw HEX나 gradient를 두지 않는다', () => {
    const publicStyles = [shell, map, exploration, detail, responsive].join('\n');

    expect(designSystem).toContain('--hs-color-primary: #0e6b5e;');
    expect(designSystem).toContain('--hs-surface-page: var(--hs-color-surface-muted);');
    expect(publicStyles).not.toMatch(/#[0-9a-fA-F]{3,8}\b/);
    expect(publicStyles).not.toMatch(/(?:linear|radial|conic)-gradient\s*\(/);
    expect(designSystem).not.toMatch(/(?:linear|radial|conic)-gradient\s*\(/);
  });

  it('header와 filter bar를 그라데이션 없는 단색 surface와 경계로 분리한다', () => {
    expect(shell).toContain('background: var(--hs-map-color-surface);');
    expect(shell).toContain('.app-bar {');
    expect(shell).toContain('box-shadow: none;');
    expect(shell).toContain('.app-brand-mark {');
    expect(shell).toContain('object-fit: cover;');
    expect(map).toContain('.filter-panel {');
    expect(map).toContain('border-bottom: 1px solid var(--hs-map-color-line);');
    expect(map).toContain('background: var(--hs-map-color-surface);');
    expect(map).not.toContain('.filter-panel::after');
  });

  it('상세 mode에서 바깥 탐색 header를 완전히 숨긴다', () => {
    expect(exploration).toContain('.exploration-panel-header[hidden] { display: none; }');
  });

  it('상세 identity와 snapshot은 380px rail에서 충분한 세로 공간과 타이포 계층을 갖는다', () => {
    expect(detail).toContain('min-height: 76px;');
    expect(detail).toContain('.detail-drawer-identity h2');
    expect(detail).toContain('font-size: 16px;');
    expect(detail).toContain('.detail-key-stats {');
    expect(detail).toContain('min-height: 84px;');
    expect(detail).toContain('.detail-key-stats .detail-metric:first-child dd { font-size: 18px;');
    expect(detail).toContain('.detail-key-stats .detail-metric:last-child dd {');
    expect(detail).toContain('white-space: normal;');
    expect(detail).toContain('.prediction-panel {');
    expect(detail).toContain('background: var(--hs-map-color-brand-soft);');
    expect(detail).toContain('border-left: 3px solid var(--hs-map-color-marker-accent);');
    expect(detail).toContain('.trade-section-header h3 { margin: 0; font-size: 14px;');
    expect(detail).toContain('.trade-range-button { min-height: 34px;');
  });

  it('공개 지도 local SVG icon export를 dependency 없이 유지한다', () => {
    for (const icon of ['HomeSearchLogoIcon', 'SearchIcon', 'RefreshIcon', 'ChevronDownIcon', 'BackIcon', 'CloseIcon', 'PlusIcon', 'MinusIcon', 'HelpIcon', 'CheckIcon']) {
      expect(icons).toContain(`export function ${icon}`);
    }
    expect(icons).toContain("stroke: 'currentColor'");
  });

  it('선택한 Home Search 이미지 로고를 header와 browser icon에 공통 적용한다', () => {
    expect(mapApp).toContain('className="app-brand-mark"');
    expect(mapApp).toContain('src="/home-search-logo.png"');
    expect(mapApp).toContain('HomeSearch · 실거래가 인사이트');
    expect(shell).toContain('object-fit: cover;');
    expect(indexHtml).toContain('href="/favicon-32.png?v=2"');
    expect(indexHtml).toContain('rel="shortcut icon"');
    expect(indexHtml).toContain('href="/apple-touch-icon.png"');
  });

  it('desktop/tablet rail과 mobile docked sheet 좌표를 같은 grid contract로 유지한다', () => {
    expect(designSystem).toContain('--hs-map-shell-bar-height: 56px;');
    expect(designSystem).toContain('--hs-map-rail-width-wide: 380px;');
    expect(designSystem).toContain('--hs-map-rail-width-standard: 380px;');
    expect(designSystem).toContain('--hs-map-rail-width-compact: 352px;');
    expect(shell).toContain('grid-template-columns: var(--hs-map-rail-width) minmax(0, 1fr);');
    expect(shell).toContain('grid-template-rows: var(--hs-map-filter-height) minmax(0, 1fr);');
    expect(responsive).toContain('grid-template-columns: var(--hs-map-rail-width-compact) minmax(0, 1fr);');
    expect(responsive).toContain('minmax(242px, 1fr) min(46dvh, calc(100dvh - 300px))');
    expect(responsive).toContain('minmax(242px, 1fr) min(68dvh, calc(100dvh - 300px))');
    expect(exploration).not.toContain('404px');
    expect(responsive).toContain('(orientation: landscape)');
    expect(responsive).toContain('320px minmax(0, 1fr)');
  });

  it('탐색 text와 filter range가 좁은 폭에서도 깨지지 않는 sizing contract를 유지한다', () => {
    expect(exploration).toContain('.panel-list-title');
    expect(exploration).toContain('.panel-list-meta');
    expect(exploration).toContain('.region-breadcrumb-link');
    expect(exploration).toContain('word-break: keep-all;');
    expect(map).toContain('grid-template-columns: minmax(0, 1fr) 40px minmax(0, 1fr);');
    expect(map).toContain('min-inline-size: 0;');
    expect(map).toContain('.filter-number-field-unit');
    expect(map).toContain('white-space: nowrap;');
    expect(map).toContain('grid-template-columns: minmax(0, 1fr) auto;');
    expect(map).toContain('.filter-chip-list { display: flex; height: 42px; align-items: center;');
    expect(map).toContain('.filter-chip-copy { display: grid; height: 100%; place-content: center;');
    expect(map).not.toContain('.filter-status');
    expect(exploration).not.toContain('.region-step-summary');
  });

  it('필터 대표 라벨과 popover 제목을 크게 정렬하고 장식성 hover를 사용하지 않는다', () => {
    expect(map).toContain('.filter-panel {');
    expect(map).toContain('grid-template-columns: minmax(0, 1fr);');
    expect(map).toContain('.filter-chip-copy > span:first-child { display: block; font-size: 14px; font-weight: 800; line-height: 18px;');
    expect(map).toContain('border-color: var(--hs-map-color-marker-soft); background: var(--hs-map-color-brand-soft);');
    expect(map).toContain('.filter-reset { display: inline-flex;');
    expect(map).toContain('border-color: transparent; border-radius: var(--hs-map-radius-pill); background: transparent;');
    expect(map).toContain('.filter-popover legend { float: left; width: 100%; margin: 0 0 8px;');
    expect(map).toContain('font-size: 15px; font-weight: 800; line-height: 20px;');
    expect(map).toContain('font-size: 13px; font-weight: 750; line-height: 18px;');
    expect(map).not.toContain('.filter-chip:hover, .filter-reset:hover');
    expect(map).not.toContain('.filter-popover-actions button:last-child:hover');
  });

  it('범위 조정 control에 파란 선을 표시하지 않고 회색 focus 신호를 유지한다', () => {
    expect(map).toContain('.filter-slider-track span {');
    expect(map).toContain('background: var(--hs-map-color-line-strong);');
    expect(map).not.toContain('.filter-slider-track span { position: absolute; top: 0; bottom: 0; left: var(--filter-min); right: calc(100% - var(--filter-max)); background: var(--hs-map-color-action); }');
    expect(map).toContain('.filter-slider input[type="range"]:focus-visible { outline: none; }');
    expect(map).toContain('.filter-popover .filter-number-field input:focus-visible { outline: none; }');
    expect(map).toContain('.filter-number-field:focus-within { border-color: var(--hs-map-color-line-strong);');
  });

  it('지역 타일을 Sky surface로 정렬하고 라벨을 정중앙에 고정한다', () => {
    expect(exploration).toContain('.region-grid-list button {');
    expect(exploration).toContain('position: relative;');
    expect(exploration).toContain('display: flex;');
    expect(exploration).not.toContain('grid-template-columns: minmax(0, 1fr) 14px;');
    expect(exploration).toContain('padding: 8px 12px;');
    expect(exploration).toContain('min-height: 56px;');
    expect(exploration).toContain('overflow-wrap: anywhere;');
    expect(exploration).toContain('-webkit-line-clamp: 2;');
    expect(exploration).toContain('.region-grid-list button svg { position: absolute; top: 8px; right: 8px;');
    expect(exploration).toContain('background: var(--hs-map-color-surface);');
    expect(exploration).toContain('color: var(--hs-map-color-ink);');
    expect(exploration).toContain('.region-grid-list button:hover { border-color: var(--hs-map-color-primary); background: var(--hs-map-color-brand-soft); color: var(--hs-map-color-action); }');
    expect(exploration).toContain('.region-grid-list button[aria-pressed="true"] { border-color: var(--hs-map-color-action); background: var(--hs-map-color-brand-soft); color: var(--hs-map-color-action); }');
  });

  it('지역 breadcrumb를 박스 장식 없는 가벼운 텍스트 탐색으로 유지한다', () => {
    expect(exploration).toContain('padding: 0 2px 8px;');
    expect(exploration).toContain('border-bottom: 1px solid var(--hs-map-color-line);');
    expect(exploration).toContain('.region-breadcrumb-link[aria-current="page"] { color: var(--hs-map-color-ink) !important; font-weight: 800; }');
    expect(exploration).toContain('.region-breadcrumb-step > svg { width: 14px; height: 14px;');
    expect(exploration).toContain('color: var(--hs-map-color-line-strong); stroke-width: 2;');
    expect(exploration).not.toContain('box-shadow: inset 0 0 0 1px var(--hs-map-color-primary);');
    expect(exploration).not.toContain('border-radius: var(--hs-map-radius-pill); background: var(--hs-map-color-surface); color: var(--hs-map-color-action); stroke-width: 2.25;');
  });

  it('지역 단지 목록을 외곽 card 없이 비교 가능한 2열 행으로 유지한다', () => {
    expect(exploration).toContain('.region-complex-section { display: grid; gap: 8px;');
    expect(exploration).toContain('border-top: 1px solid var(--hs-map-color-line); border-bottom: 1px solid var(--hs-map-color-line);');
    expect(exploration).toContain('.region-complex-list .region-complex-card {');
    expect(exploration).toContain('min-height: 76px;');
    expect(exploration).toContain('grid-template-columns: minmax(0, 1fr) auto;');
    expect(exploration).toContain('.region-complex-name {');
    expect(exploration).toContain('font-size: 15px; font-weight: 750;');
    expect(exploration).toContain('.region-complex-stats { display: grid;');
    expect(exploration).toContain('.region-complex-unit { color: var(--hs-map-color-ink); font-size: 13px;');
  });
});
