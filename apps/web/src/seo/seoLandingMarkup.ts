import type {SeoPage} from './types';

export function renderSeoLandingMarkup(page: SeoPage): string {
  const breadcrumbs = page.data.breadcrumbs
    .map((item) => ` / <a href="/regions/${positiveId(item.regionId)}">${escapeMarkup(item.name)}</a>`)
    .join('');
  const title = page.kind === 'complex'
    ? `${escapeMarkup(page.data.name)} 실거래가·단지정보`
    : `${escapeMarkup(page.data.name)} 아파트 실거래가`;
  const body = page.kind === 'complex'
    ? renderComplexMarkup(page.data)
    : renderRegionMarkup(page.data);
  return `<main class="seo-landing"><nav aria-label="지역 경로"><a href="/">홈</a>${breadcrumbs}</nav><h1>${title}</h1>${body}<p><a href="/">홈서치 지도에서 보기</a></p></main>`;
}

function renderComplexMarkup(data: Extract<SeoPage, {kind: 'complex'}>['data']): string {
  const details = [
    data.dongCount == null ? '' : `<dt>동 수</dt><dd>${data.dongCount.toLocaleString('ko-KR')}개 동</dd>`,
    data.unitCount == null ? '' : `<dt>세대 수</dt><dd>${data.unitCount.toLocaleString('ko-KR')}세대</dd>`,
    data.useApprovalDate == null ? '' : `<dt>사용승인일</dt><dd>${escapeMarkup(data.useApprovalDate)}</dd>`,
  ].join('');
  const trades = data.recentTrades.length === 0
    ? '<p>공개된 최근 실거래가 없습니다.</p>'
    : `<ul>${data.recentTrades.map((trade) => {
      const area = trade.exclusiveArea == null ? '' : ` · 전용 ${trade.exclusiveArea.toLocaleString('ko-KR')}㎡`;
      const floor = trade.floor == null ? '' : ` · ${trade.floor.toLocaleString('ko-KR')}층`;
      return `<li>${escapeMarkup(trade.dealDate)} · ${trade.dealAmount.toLocaleString('ko-KR')}만원${area}${floor}</li>`;
    }).join('')}</ul>`;
  return `<p>${escapeMarkup(data.address)}에 있는 ${escapeMarkup(data.name)}의 최근 아파트 실거래가와 단지정보입니다.</p><dl>${details}</dl><h2>최근 실거래</h2>${trades}`;
}

function renderRegionMarkup(data: Extract<SeoPage, {kind: 'region'}>['data']): string {
  const complexes = data.representativeComplexes.length === 0
    ? '<p>공개할 수 있는 대표 단지가 아직 없습니다.</p>'
    : `<ul>${data.representativeComplexes.map((complex) => `<li><a href="/complexes/${positiveId(complex.complexId)}">${escapeMarkup(complex.name)}</a> · ${escapeMarkup(complex.address)}</li>`).join('')}</ul>`;
  return `<p>${escapeMarkup(data.name)}의 아파트 실거래가와 대표 단지를 확인하세요. 색인 가능한 단지는 ${data.indexableComplexCount.toLocaleString('ko-KR')}곳입니다.</p><h2>대표 단지</h2>${complexes}`;
}

function escapeMarkup(value: string): string {
  return value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;').replaceAll("'", '&#39;');
}

function positiveId(value: number): number {
  if (!Number.isSafeInteger(value) || value <= 0) throw new Error('SEO identifier must be a positive safe integer');
  return value;
}
