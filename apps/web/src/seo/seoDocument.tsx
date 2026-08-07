import {renderToStaticMarkup} from 'react-dom/server';
import {renderSeoLandingMarkup} from './seoLandingMarkup';
import type {SeoPage} from './types';
import {LegalPage,type LegalPageKind} from '../legal/LegalPage';
import {GoogleAnalyticsConsent} from '../shared/analytics/GoogleAnalyticsConsent';

const DEFAULT_TEMPLATE='<!doctype html><html lang="ko"><head><meta charset="UTF-8"></head><body><div id="root"></div></body></html>';
export function renderSeoDocument(page:SeoPage,template=DEFAULT_TEMPLATE):string {
  const metadata=pageMetadata(page); const content=renderSeoLandingMarkup(page);
  const head=[`<title>${escapeHtml(metadata.title)}</title>`,`<meta name="description" content="${escapeHtml(metadata.description)}">`,`<meta name="robots" content="${page.data.indexable?'index,follow':'noindex,follow'}">`,`<link rel="canonical" href="${escapeHtml(metadata.canonical)}">`,'<meta property="og:type" content="website">',`<meta property="og:title" content="${escapeHtml(metadata.title)}">`,`<meta property="og:description" content="${escapeHtml(metadata.description)}">`,`<meta property="og:url" content="${escapeHtml(metadata.canonical)}">`,`<meta property="og:image" content="${escapeHtml(`${page.canonicalOrigin}/home-search-logo.png`)}">`,'<meta name="twitter:card" content="summary_large_image">',`<meta name="twitter:title" content="${escapeHtml(metadata.title)}">`,`<meta name="twitter:description" content="${escapeHtml(metadata.description)}">`,`<meta name="twitter:image" content="${escapeHtml(`${page.canonicalOrigin}/home-search-logo.png`)}">`,`<script type="application/ld+json">${safeJson(structuredData(page,metadata.canonical))}</script>`].join('');
  const cleaned=template
    .replace(/<title>[\s\S]*?<\/title>/iu,'')
    .replace(/<meta\s+name="description"[^>]*>/giu,'')
    .replace(/<link\s+rel="canonical"[^>]*>/giu,'')
    .replace(/<meta\s+property="og:[^"]+"[^>]*>/giu,'')
    .replace(/<meta\s+name="twitter:[^"]+"[^>]*>/giu,'');
  return cleaned.replace('</head>',`${head}</head>`).replace('<div id="root"></div>',`<div id="root">${content}</div><script id="home-seo-static" type="text/plain">static</script>`);
}
export function renderLegalDocument(kind:LegalPageKind,origin:string,template=DEFAULT_TEMPLATE):string {
  const metadata={
    privacy:{title:'개인정보처리방침 | 홈서치',description:'홈서치 개인정보처리방침',path:'/privacy'},
    terms:{title:'서비스 이용약관 | 홈서치',description:'홈서치 서비스 이용약관',path:'/terms'},
    about:{title:'홈서치 소개 | 아파트 실거래가 지도',description:'지도에서 아파트 실거래가와 지역별 시세를 탐색하는 홈서치를 소개합니다.',path:'/about'},
  }[kind];
  const canonical=`${origin}${metadata.path}`;
  const content=renderToStaticMarkup(<><LegalPage kind={kind}/><GoogleAnalyticsConsent/></>);
  const head=`<title>${metadata.title}</title><meta name="description" content="${metadata.description}"><meta name="robots" content="index,follow"><link rel="canonical" href="${canonical}"><meta property="og:type" content="website"><meta property="og:title" content="${metadata.title}"><meta property="og:description" content="${metadata.description}"><meta property="og:url" content="${canonical}">`;
  const cleaned=template.replace(/<title>[\s\S]*?<\/title>/iu,'').replace(/<meta\s+name="description"[^>]*>/giu,'').replace(/<link\s+rel="canonical"[^>]*>/giu,'').replace(/<meta\s+property="og:[^"]+"[^>]*>/giu,'');
  return cleaned.replace('</head>',`${head}</head>`).replace('<div id="root"></div>',`<div id="root">${content}</div><script id="home-legal-page" type="text/plain">${kind}</script>`);
}
function pageMetadata(page:SeoPage){return page.kind==='complex'?{title:`${page.data.name} 실거래가·단지정보 | 홈서치`,description:`${page.data.address} ${page.data.name}의 최근 아파트 실거래가, 세대 수, 사용승인일을 확인하세요.`,canonical:`${page.canonicalOrigin}/complexes/${page.data.complexId}`}:{title:`${page.data.name} 아파트 실거래가 | 홈서치`,description:`${page.data.name}의 아파트 실거래가와 대표 단지 정보를 홈서치에서 확인하세요.`,canonical:`${page.canonicalOrigin}/regions/${page.data.regionId}`};}
function structuredData(page:SeoPage,canonical:string){const items=[{'@type':'ListItem',position:1,name:'홈',item:`${page.canonicalOrigin}/`},...page.data.breadcrumbs.map((item,index)=>({'@type':'ListItem',position:index+2,name:item.name,item:`${page.canonicalOrigin}/regions/${item.regionId}`}))];const entity=page.kind==='complex'?{'@type':'ApartmentComplex',name:page.data.name,address:page.data.address,url:canonical}:{'@type':'Place',name:page.data.name,url:canonical};return {'@context':'https://schema.org','@graph':[{'@type':'WebSite',name:'홈서치',url:`${page.canonicalOrigin}/`},{'@type':'Organization',name:'홈서치',url:`${page.canonicalOrigin}/`},{'@type':'BreadcrumbList',itemListElement:items},entity]};}
function escapeHtml(value:string){return value.replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;');}
function safeJson(value:unknown){return JSON.stringify(value).replaceAll('<','\\u003c').replaceAll('>','\\u003e').replaceAll('&','\\u0026');}
