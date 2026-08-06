import { renderLegalDocument, renderSeoDocument } from './seoDocument';
import { buildComplexSitemaps, buildPagesSitemap } from './sitemap';

describe('SEO renderer', () => {
  it('단지별 canonical과 JSON-LD를 만들고 외부 문자열을 escape한다', () => {
    const html = renderSeoDocument({
      kind: 'complex',
      canonicalOrigin: 'https://homesearch.world',
      data: {
        complexId: 501,
        name: '<표본&아파트>',
        address: '서울 표본구',
        indexable: true,
        dongCount: 8,
        unitCount: 740,
        useApprovalDate: '2015-03-20',
        hasBuildingInfo: true,
        breadcrumbs: [{ regionId: 1, name: '서울' }],
        recentTrades: [],
      },
    }, '<!doctype html><html><head><meta property="og:title" content="root title"></head><body><div id="root"></div></body></html>');

    expect(html).toContain('<title>&lt;표본&amp;아파트&gt; 실거래가·단지정보 | 홈서치</title>');
    expect(html).toContain('rel="canonical" href="https://homesearch.world/complexes/501"');
    expect(html).toContain('"@type":"ApartmentComplex"');
    expect(html).toContain('<script id="home-seo-page" type="application/json">');
    expect(html).not.toContain('window.__HOME_SEO_PAGE__');
    expect(html).not.toContain('root title');
    expect(html.match(/property="og:title"/gu)).toHaveLength(1);
    expect(html).not.toContain('<표본&아파트>');
  });

  it('단지 sitemap을 10,000개 단위로 중복 없이 분할한다', () => {
    const ids = Array.from({ length: 10_001 }, (_, index) => index + 1);
    const sitemaps = buildComplexSitemaps('https://homesearch.world', ids);

    expect(sitemaps).toHaveLength(2);
    expect(sitemaps[0].match(/<url>/gu)).toHaveLength(10_000);
    expect(sitemaps[1].match(/<url>/gu)).toHaveLength(1);
    expect(new Set(ids)).toHaveLength(ids.length);
  });

  it('법적 페이지를 실제 HTML과 고유 canonical로 렌더링한다', () => {
    const html = renderLegalDocument('privacy', 'https://homesearch.world');

    expect(html).toContain('<h1>개인정보처리방침</h1>');
    expect(html).toContain('gwangjae.kwon.99@gmail.com');
    expect(html).toContain('rel="canonical" href="https://homesearch.world/privacy"');
    expect(html).toContain('id="home-legal-page"');
    expect(html).not.toContain('googletagmanager.com');
  });

  it('고정 페이지 sitemap에 법적·소개 페이지를 포함한다', () => {
    const xml = buildPagesSitemap('https://homesearch.world');

    expect(xml).toContain('<loc>https://homesearch.world/privacy</loc>');
    expect(xml).toContain('<loc>https://homesearch.world/terms</loc>');
    expect(xml).toContain('<loc>https://homesearch.world/about</loc>');
  });
});
