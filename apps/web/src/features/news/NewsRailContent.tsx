import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';

import { ChevronRightIcon } from '../../shared/icons';
import { RegionChoiceGrid } from '../exploration/RegionChoiceGrid';
import { fetchRootRegions, type RegionSummary } from '../region/api/fetchRegions';
import type { MarketNewsCategory, MarketNewsScopeType } from './api/fetchMarketNews';
import { NewsRows } from './NewsRows';
import { categoryLabel } from './newsLabels';
import { useMarketNews } from './hooks/useMarketNews';

const CATEGORIES: MarketNewsCategory[] = [
  'ALL', 'POLICY', 'FINANCE_LOAN', 'SUPPLY_SALE', 'REDEVELOPMENT',
  'TRANSACTION_PRICE', 'TRANSPORT_DEVELOPMENT',
];
const NATIONWIDE_REGION: RegionSummary = { id: 0, code: '', name: '전국' };

export function NewsRailContent({ active }: { active: boolean }) {
  const location = useLocation();
  const navigate = useNavigate();
  const params = new URLSearchParams(location.search);
  const scope: MarketNewsScopeType = params.get('scope') === 'NATIONWIDE' ? 'NATIONWIDE' : 'SIDO';
  const regionCode = scope === 'SIDO' ? params.get('regionCode') ?? '11' : null;
  const rawCategory = params.get('category') as MarketNewsCategory | null;
  const category = CATEGORIES.includes(rawCategory ?? 'ALL') ? rawCategory ?? 'ALL' : 'ALL';
  const { data, loadMore, retry, state } = useMarketNews({ active, category, regionCode, scope });
  const [regions, setRegions] = useState<RegionSummary[]>([]);
  const [chooserOpen, setChooserOpen] = useState(false);
  const tabRefs = useRef<Partial<Record<MarketNewsCategory, HTMLButtonElement | null>>>({});
  const listRef = useRef<HTMLDivElement | null>(null);
  const savedScrollRef = useRef(0);

  useEffect(() => {
    if (!active || regions.length > 0) return undefined;
    let canceled = false;
    fetchRootRegions().then((next) => { if (!canceled) setRegions(next); }).catch(() => undefined);
    return () => { canceled = true; };
  }, [active, regions.length]);

  useEffect(() => {
    if (!active) return undefined;
    queueMicrotask(() => {
      if (listRef.current) listRef.current.scrollTop = savedScrollRef.current;
    });
    const list = listRef.current;
    return () => {
      if (list) savedScrollRef.current = list.scrollTop;
    };
  }, [active]);

  const regionName = scope === 'NATIONWIDE'
    ? '전국'
    : shortRegionName(regions.find((region) => region.code === regionCode)?.name ?? '서울특별시');

  function updateRoute(nextScope: MarketNewsScopeType, nextRegionCode: string | null, nextCategory: MarketNewsCategory) {
    const next = new URLSearchParams();
    next.set('scope', nextScope);
    if (nextScope === 'SIDO' && nextRegionCode) next.set('regionCode', nextRegionCode);
    next.set('category', nextCategory);
    navigate(`/insights/news?${next.toString()}`);
  }

  if (!active) {
    return <section aria-label="부동산 뉴스" className="news-rail-content" hidden />;
  }

  return (
    <section aria-label="부동산 뉴스" className="news-rail-content" hidden={!active}>
      <header className="news-context-header">
        <div>
          <strong>{regionName}</strong>
          <button
            aria-expanded={chooserOpen}
            type="button"
            onClick={() => setChooserOpen((value) => !value)}
          >
            지역 변경 <ChevronRightIcon aria-hidden="true" />
          </button>
        </div>
        <p>최근 30일 수집 뉴스</p>
        <span>{data?.generatedAt ? `${formatCollectionTime(data.generatedAt)} 수집` : '수집 시각 확인 중'}</span>
      </header>
      <div aria-label="뉴스 카테고리" className="news-category-tabs" role="tablist">
        {CATEGORIES.map((value) => (
          <button
            aria-selected={category === value}
            key={value}
            ref={(element) => { tabRefs.current[value] = element; }}
            role="tab"
            tabIndex={category === value ? 0 : -1}
            type="button"
            onClick={() => updateRoute(scope, regionCode, value)}
            onKeyDown={(event) => moveTab(event, value, (next) => updateRoute(scope, regionCode, next), tabRefs.current)}
          >
            {value === 'ALL' ? '전체' : categoryLabel(value)}
          </button>
        ))}
      </div>
      {chooserOpen ? (
        <div className="news-region-chooser">
          <RegionChoiceGrid
            ariaLabel="뉴스 지역 선택"
            onSelect={(region) => {
              updateRoute(region.id === 0 ? 'NATIONWIDE' : 'SIDO', region.id === 0 ? null : region.code, category);
              setChooserOpen(false);
            }}
            regions={[NATIONWIDE_REGION, ...regions]}
            selectedCode={regionCode ?? ''}
            selectionLabel="뉴스 지역 선택"
          />
        </div>
      ) : (
        <div className="news-list-scroll" ref={listRef}>
          {data?.dataStatus === 'STALE' ? <p className="news-status-copy">새 뉴스 확인이 늦어지고 있어요</p> : null}
          {state === 'loading' ? <NewsSkeleton /> : null}
          {state === 'error' ? (
            <div className="news-empty-state" role="alert">
              <p>뉴스를 불러오지 못했어요</p>
              <button type="button" onClick={retry}>다시 시도</button>
            </div>
          ) : null}
          {state === 'ready' && data?.dataStatus === 'UNAVAILABLE' ? (
            <p className="news-empty-state">뉴스를 준비하고 있어요</p>
          ) : null}
          {state === 'ready' && data?.dataStatus !== 'UNAVAILABLE' && data?.items.length === 0 ? (
            <p className="news-empty-state">최근 30일에 수집된 뉴스가 없어요</p>
          ) : null}
          {data?.items.length ? <NewsRows items={data.items} /> : null}
          {data?.nextCursor ? (
            <button className="news-load-more" disabled={state === 'loading-more'} type="button" onClick={loadMore}>
              {state === 'loading-more' ? '불러오는 중' : '더 보기'}
            </button>
          ) : null}
        </div>
      )}
    </section>
  );
}

function NewsSkeleton() {
  return <div aria-label="뉴스를 불러오는 중" className="news-loading" role="status">
    {Array.from({ length: 20 }, (_, index) => <span key={index} />)}
  </div>;
}

function moveTab(
  event: KeyboardEvent<HTMLButtonElement>,
  current: MarketNewsCategory,
  select: (category: MarketNewsCategory) => void,
  refs: Partial<Record<MarketNewsCategory, HTMLButtonElement | null>>,
) {
  const index = CATEGORIES.indexOf(current);
  let nextIndex: number | null = null;
  if (event.key === 'ArrowRight') nextIndex = (index + 1) % CATEGORIES.length;
  if (event.key === 'ArrowLeft') nextIndex = (index - 1 + CATEGORIES.length) % CATEGORIES.length;
  if (event.key === 'Home') nextIndex = 0;
  if (event.key === 'End') nextIndex = CATEGORIES.length - 1;
  if (nextIndex == null) return;
  event.preventDefault();
  const next = CATEGORIES[nextIndex];
  select(next);
  queueMicrotask(() => refs[next]?.focus());
}

function shortRegionName(name: string): string {
  return name.replace(/특별자치도|특별자치시|특별시|광역시|도$/u, '') || name;
}

function formatCollectionTime(value: string): string {
  const date = new Date(value);
  const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' }).format(new Date());
  const datePart = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' }).format(date);
  const time = new Intl.DateTimeFormat('ko-KR', {
    hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Seoul',
  }).format(date);
  const [, month, day] = datePart.split('-');
  return datePart === today ? `오늘 ${time}` : `${Number(month)}.${Number(day)} ${time}`;
}
