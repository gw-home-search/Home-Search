import { lazy, Suspense, useEffect, useRef, useState, type KeyboardEvent } from 'react';

import type { ComplexDetail, PricePrediction } from './api/fetchComplexDetail';
import type { ParcelComplexSummary } from './api/fetchParcelComplexes';
import type { ParcelTrades, TradeItem } from './api/fetchParcelTrades';
import type { TradeTrendPoint } from './api/fetchTradeTrend';
import type { RegionComplexSummary } from '../region/api/fetchRegions';
import { RequestStateNotice } from '../../shared/RequestStateNotice';
import { BackIcon, CloseIcon, HelpIcon } from '../../shared/icons';
import { FavoriteToggle } from '../favorites/FavoriteToggle';
import type { FavoriteState } from '../favorites/favoriteTypes';

type DetailRequestState = 'idle' | 'loading' | 'ready' | 'error';

type ComplexSelection = {
  parcelId: number | null;
  complexId: number | null;
};

type DetailSidebarProps = {
  complexDetail: ComplexDetail | null;
  detailError: string | null;
  detailState: DetailRequestState;
  favoriteError?: string | null;
  favoriteLiveMessage?: string;
  favoriteState?: FavoriteState;
  onBack: () => void;
  onClose?: () => void;
  onComplexSelect: (complex: ParcelComplexSummary | RegionComplexSummary) => void;
  onRetryDetail: () => void;
  onRetryTrades?: () => void;
  onRetryTrend?: () => void;
  onLoadMoreTrades: () => void;
  onFavoriteToggle?: (trigger?: HTMLElement) => void;
  onRetryFavorite?: () => void;
  parcelComplexes: ParcelComplexSummary[];
  parcelTrades: ParcelTrades | null;
  tradeTrend: TradeTrendPoint[];
  tradeRows: TradeItem[];
  tradeError?: string | null;
  tradeState?: DetailRequestState;
  trendError?: string | null;
  trendState?: DetailRequestState;
  selection: ComplexSelection;
};

const TradeTrendChart = lazy(() =>
  import('./TradeTrendChart').then((module) => ({ default: module.TradeTrendChart })));

type DetailMobileTab = 'info' | 'trend' | 'trades';

export function DetailSidebar({
  complexDetail,
  detailError,
  detailState,
  favoriteError = null,
  favoriteLiveMessage = '',
  favoriteState = { phase: 'auth-checking', favorite: null },
  onBack,
  onClose = onBack,
  onComplexSelect,
  onRetryDetail,
  onRetryTrades = onRetryDetail,
  onRetryTrend = onRetryDetail,
  onLoadMoreTrades,
  onFavoriteToggle = () => undefined,
  onRetryFavorite = () => undefined,
  parcelComplexes,
  parcelTrades,
  tradeTrend,
  tradeRows,
  tradeError = null,
  tradeState = 'ready',
  trendError = null,
  trendState = 'ready',
  selection,
}: DetailSidebarProps) {
  const [mobileTab, setMobileTab] = useState<DetailMobileTab>('info');
  const tabRefs = useRef<Partial<Record<DetailMobileTab, HTMLButtonElement | null>>>({});
  const isMobileLayout = useMediaQuery('(max-width: 720px)');
  const shouldRenderTradeChart = !isMobileLayout || mobileTab === 'trend';

  return (
    <section aria-label="단지 상세 패널" className="detail-sidebar" data-ui-layer="detail-sidebar">
      <span className="detail-sheet-handle" aria-hidden="true" />
      <div className="detail-drawer-header" data-detail-order="identity" data-detail-section="identity">
        <button
          type="button"
          aria-label="상세에서 뒤로가기"
          className="detail-back-button"
          onClick={onBack}
        >
          <BackIcon aria-hidden="true" />
        </button>
        <div className="detail-drawer-identity">
          <p className="detail-drawer-address">{complexDetail ? formatAddress(complexDetail.address) : detailDrawerKicker(selection)}</p>
          <h2>{complexDetail?.displayName ?? complexDetail?.name ?? '단지 상세'}</h2>
        </div>
        <div className="detail-header-actions">
          <FavoriteToggle state={favoriteState} liveMessage={favoriteLiveMessage} onToggle={onFavoriteToggle} />
          <button
            type="button"
            aria-label="상세 닫기"
            className="detail-close-button"
            onClick={onClose}
          >
            <CloseIcon aria-hidden="true" />
          </button>
        </div>
      </div>

      {favoriteError == null ? null : (
        <div className="favorite-error-row" role="status">
          <span>{favoriteError}</span>
          {favoriteError.includes('최대 200개') ? null : <button type="button" onClick={onRetryFavorite}>다시 시도</button>}
        </div>
      )}

      <div className="detail-mobile-tabs" role="tablist" aria-label="상세 섹션">
        {([
          ['info', '정보'],
          ['trend', '시세'],
          ['trades', parcelTrades == null ? '거래' : `거래 ${parcelTrades.totalElements.toLocaleString()}`],
        ] as Array<[DetailMobileTab, string]>).map(([tab, label]) => (
          <button
            ref={(element) => { tabRefs.current[tab] = element; }}
            type="button"
            role="tab"
            key={tab}
            id={`detail-tab-${tab}`}
            aria-controls={`detail-tabpanel-${tab}`}
            aria-label={`${tab === 'trades' ? '거래' : label} 보기`}
            aria-selected={mobileTab === tab}
            tabIndex={mobileTab === tab ? 0 : -1}
            onClick={() => setMobileTab(tab)}
            onKeyDown={(event) => moveDetailTabFocus(event, tab, setMobileTab, tabRefs.current)}
          >
            {label}
          </button>
        ))}
      </div>

      {detailState === 'ready' ? null : <ul
        aria-label="상세 API 데이터 요약"
        className="data-status-list"
        data-api-flow="detail"
        data-detail-order="status"
      >
        <li><span>상세</span><strong>{detailStateLabel(detailState)}</strong></li>
        <li><span>실거래</span><strong>{parcelTrades == null ? '대기' : `${parcelTrades.totalElements.toLocaleString()}건`}</strong></li>
        <li><span>같은 필지</span><strong>대기</strong></li>
      </ul>}

      <RequestStateNotice
        state={detailState}
        loadingMessage="단지 상세를 불러오는 중"
        emptyMessage="표시할 단지 상세가 없습니다"
        errorMessage="단지 상세를 불러오지 못했어요"
        technicalError={detailError}
        onRetry={onRetryDetail}
      />

      {detailState === 'ready' && complexDetail ? (
        <>
          <section
            className="detail-price-overview detail-tab-panel"
            data-detail-order="summary"
            data-mobile-tab-panel="trend"
            data-mobile-tab-active={mobileTab === 'trend' ? 'true' : 'false'}
          >
            <dl className="detail-key-stats">
              <div className="detail-metric"><dt>최근 거래</dt><dd>{latestTradeAmountLabel(parcelTrades?.trades ?? [])}</dd></div>
              <div className="detail-metric"><dt>단지 규모</dt><dd>{complexScaleLabel(complexDetail)}</dd></div>
            </dl>
            <PricePredictionPanel prediction={complexDetail.prediction} />
          </section>

          <section
            aria-label="같은 필지 단지 선택"
            className="detail-complex-switcher detail-tab-panel"
            data-detail-order="switcher"
            data-mobile-tab-panel="info"
            data-mobile-tab-active={mobileTab === 'info' ? 'true' : 'false'}
            hidden={parcelComplexes.length <= 1}
          >
            <div className="detail-section-heading"><h3>같은 필지 단지</h3><span>{parcelComplexes.length.toLocaleString()}개</span></div>
            <ul>
              {parcelComplexes.map((complex) => (
                <li key={complex.complexId}>
                  <button type="button" aria-label={`같은 필지 단지 선택 ${complex.complexName}`} aria-current={complex.complexId === complexDetail.complexId ? 'true' : undefined} onClick={() => onComplexSelect(complex)}>
                    <span>{complex.complexName}</span><span>{complexSummaryMeta(complex)}</span>
                  </button>
                </li>
              ))}
            </ul>
          </section>

          <section
            id="detail-tabpanel-info"
            role="tabpanel"
            aria-labelledby="detail-tab-info"
            className="detail-basic-information detail-tab-panel"
            data-detail-order="information"
            data-mobile-tab-panel="info"
            data-mobile-tab-active={mobileTab === 'info' ? 'true' : 'false'}
          >
            <h3>단지 기본정보</h3>
            <dl>
              {detailInformationRow('주소', formatAddress(complexDetail.address), 'address')}
              {detailInformationRow('사용승인일', complexDetail.useDate)}
              {detailInformationRow('세대수', formatNumber(complexDetail.unitCnt, '세대'), 'unitCnt')}
              {detailInformationRow('동수', formatNumber(complexDetail.dongCnt, '개동'))}
            </dl>
            <details className="detail-additional-information">
              <summary>추가 정보</summary>
              <dl>
                {detailInformationRow('거래명', complexDetail.tradeName)}
                {detailInformationRow('단지명', complexDetail.name)}
                {detailInformationRow('면적', areaSummary(complexDetail))}
                {detailInformationRow('건폐율', formatNumber(complexDetail.bcRat, '%'))}
                {detailInformationRow('용적률', formatNumber(complexDetail.vlRat, '%'))}
              </dl>
            </details>
          </section>

          <div id="detail-tabpanel-trend" role="tabpanel" aria-labelledby="detail-tab-trend" className="detail-tab-panel" data-detail-order="trend" data-mobile-tab-panel="trend" data-mobile-tab-active={mobileTab === 'trend' ? 'true' : 'false'}>
            <RequestStateNotice
              state={trendState}
              loadingMessage="시세를 불러오는 중"
              emptyMessage="표시할 시세가 없습니다"
              errorMessage="시세를 불러오지 못했어요"
              technicalError={trendError}
              onRetry={onRetryTrend}
            />
            {shouldRenderTradeChart && trendState === 'ready' && tradeTrend.length === 0 ? (
              <section className="trade-chart" aria-label="거래가 차트" data-detail-section="trade-chart">
                <div className="trade-section-header"><h3>실거래가 흐름</h3></div>
                <p className="trade-chart-empty">표시할 거래가 없습니다</p>
              </section>
            ) : null}
            {shouldRenderTradeChart && trendState === 'ready' && tradeTrend.length > 0 ? (
              <Suspense fallback={<TradeChartFallback />}>
                <TradeTrendChart trend={tradeTrend} />
              </Suspense>
            ) : null}
          </div>
          <div
            id="detail-tabpanel-trades"
            role="tabpanel"
            aria-labelledby="detail-tab-trades"
            className="detail-tab-panel"
            data-detail-order="trades"
            data-mobile-tab-panel="trades"
            data-mobile-tab-active={mobileTab === 'trades' ? 'true' : 'false'}
          >
            <RequestStateNotice
              state={tradeState}
              loadingMessage="거래를 불러오는 중"
              emptyMessage="표시할 거래가 없습니다"
              errorMessage="거래를 불러오지 못했어요"
              technicalError={tradeError}
              onRetry={onRetryTrades}
            />
            <TradeList
              rows={tradeRows}
              totalElements={parcelTrades?.totalElements ?? 0}
              onLoadMore={onLoadMoreTrades}
            />
          </div>
        </>
      ) : null}
    </section>
  );
}

const DETAIL_TABS: DetailMobileTab[] = ['info', 'trend', 'trades'];

function moveDetailTabFocus(
  event: KeyboardEvent<HTMLButtonElement>,
  current: DetailMobileTab,
  select: (tab: DetailMobileTab) => void,
  refs: Partial<Record<DetailMobileTab, HTMLButtonElement | null>>,
) {
  const currentIndex = DETAIL_TABS.indexOf(current);
  let nextIndex: number | null = null;
  if (event.key === 'ArrowRight') nextIndex = (currentIndex + 1) % DETAIL_TABS.length;
  if (event.key === 'ArrowLeft') nextIndex = (currentIndex - 1 + DETAIL_TABS.length) % DETAIL_TABS.length;
  if (event.key === 'Home') nextIndex = 0;
  if (event.key === 'End') nextIndex = DETAIL_TABS.length - 1;
  if (nextIndex == null) return;
  event.preventDefault();
  const next = DETAIL_TABS[nextIndex];
  select(next);
  refs[next]?.focus();
}

function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => mediaQueryMatches(query));

  useEffect(() => {
    if (typeof window.matchMedia !== 'function') {
      return undefined;
    }

    const mediaQuery = window.matchMedia(query);
    const handleChange = (event: MediaQueryListEvent) => setMatches(event.matches);
    setMatches(mediaQuery.matches);
    mediaQuery.addEventListener('change', handleChange);
    return () => mediaQuery.removeEventListener('change', handleChange);
  }, [query]);

  return matches;
}

function mediaQueryMatches(query: string): boolean {
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia(query).matches;
}

function detailStateLabel(state: DetailRequestState): string {
  switch (state) {
    case 'idle': return '대기';
    case 'loading': return '조회 중';
    case 'ready': return '조회됨';
    case 'error': return '오류';
  }
}

function TradeChartFallback() {
  return (
    <section className="trade-chart trade-chart-fallback" aria-label="거래가 차트 불러오는 중">
      <div className="trade-section-header"><h3>실거래가 흐름</h3></div>
      <div className="trade-chart-canvas" role="status" aria-live="polite">차트 불러오는 중</div>
    </section>
  );
}

function detailDrawerKicker(selection: ComplexSelection): string {
  if (selection.parcelId == null) {
    return `단지 ${selection.complexId}`;
  }

  return selection.complexId == null
    ? `필지 ${selection.parcelId}`
    : `단지 ${selection.complexId} / 필지 ${selection.parcelId}`;
}

function formatAddress(address: string | null): string {
  return address ?? '주소 정보 없음';
}

function detailInformationRow(label: string, value: string | null, field?: string) {
  return (
    <div className="detail-information-row" key={label} data-detail-field={field}>
      <dt>{label}</dt>
      <dd>{value ?? '-'}</dd>
    </div>
  );
}

function areaSummary(detail: ComplexDetail): string | null {
  const values = [
    detail.platArea == null ? null : `대지 ${formatArea(detail.platArea)}`,
    detail.archArea == null ? null : `건축 ${formatArea(detail.archArea)}`,
    detail.totArea == null ? null : `연면적 ${formatArea(detail.totArea)}`,
  ].filter((value): value is string => value != null);
  return values.length === 0 ? null : values.join(' · ');
}

function PricePredictionPanel({ prediction }: { prediction: PricePrediction | null }) {
  if (prediction == null) {
    return null;
  }

  if (prediction.status === 'READY') {
    return (
      <section
        aria-label="AI 예상가"
        className="prediction-panel"
        data-prediction-status={prediction.status}
      >
        <div className="prediction-heading">
          <p className="prediction-kicker">AI 예상 거래가</p>
          <div className="prediction-help">
            <button
              type="button"
              aria-describedby="prediction-help-copy"
              aria-label="AI 예상가 계산 방식 안내"
              className="prediction-help-button"
            >
              <HelpIcon aria-hidden="true" />
            </button>
            <div className="prediction-help-popover" id="prediction-help-copy" role="tooltip">
              <strong>AI 예상가 안내</strong>
              <p>최근 실거래를 기준으로 면적, 층, 지역 정보를 반영해 계산한 예상가입니다.</p>
              <p>직전 거래 흐름과 월별 시장 흐름도 함께 참고합니다.</p>
              <p>예상 범위는 최근 검증 데이터의 오차를 기준으로 산정했습니다.</p>
            </div>
          </div>
        </div>
        <strong className="prediction-amount">{formatPredictionAmount(prediction.predictedDealAmount)}</strong>
        {prediction.intervalLow != null && prediction.intervalHigh != null ? (
          <p className="prediction-range">
            예상 범위 {formatAmount(prediction.intervalLow)} ~ {formatAmount(prediction.intervalHigh)}
          </p>
        ) : null}
        <p className="prediction-basis">{predictionBasisLabel(prediction)}</p>
      </section>
    );
  }

  return (
    <section
      aria-label="AI 예상가"
      className="prediction-panel"
      data-prediction-status={prediction.status}
    >
      <p className="prediction-kicker">{predictionStatusTitle(prediction)}</p>
      <p className="prediction-message">{predictionStatusMessage(prediction)}</p>
    </section>
  );
}

function predictionStatusTitle(prediction: PricePrediction): string {
  switch (prediction.status) {
    case 'PENDING':
      return 'AI 예상가 계산 중';
    case 'FAILED':
      return 'AI 예상가를 불러오지 못했습니다';
    case 'UNAVAILABLE':
      return prediction.message ?? '예측에 필요한 최근 거래가 부족합니다';
    case 'READY':
      return 'AI 예상 거래가';
  }
}

function predictionStatusMessage(prediction: PricePrediction): string {
  switch (prediction.status) {
    case 'PENDING':
      return '잠시 후 자동으로 갱신됩니다';
    case 'FAILED':
      return prediction.message ?? '실거래 정보는 계속 확인할 수 있습니다';
    case 'UNAVAILABLE':
      return prediction.message ?? '예측에 필요한 최근 거래가 부족합니다';
    case 'READY':
      return '';
  }
}

function predictionBasisLabel(prediction: PricePrediction): string {
  const values = [
    prediction.targetAreaM2 == null ? null : `기준 ${formatArea(prediction.targetAreaM2)}`,
    prediction.targetFloor == null ? null : `${prediction.targetFloor}층`,
    prediction.basisDealDate == null ? null : `최근 거래 ${prediction.basisDealDate}`,
  ].filter((value): value is string => value != null);

  return values.length === 0 ? '기준 거래 정보 없음' : values.join(' · ');
}

function formatArea(value: number): string {
  const labels = areaLabels(value);
  return `${labels.squareMeters} (${labels.pyeong})`;
}

function areaLabels(value: number): { squareMeters: string; pyeong: string } {
  return {
    squareMeters: `${value.toLocaleString(undefined, { maximumFractionDigits: 2 })}㎡`,
    pyeong: `${(value / 3.305785).toLocaleString(undefined, {
      minimumFractionDigits: 1,
      maximumFractionDigits: 1,
    })}평`,
  };
}

function formatPredictionAmount(amount: number | null): string {
  return amount == null ? '예상가 없음' : formatAmount(amount);
}

function latestTradeAmountLabel(trades: TradeItem[]): string {
  const latestTrade = trades.slice().sort(compareTradesNewestFirst)[0];
  return latestTrade == null ? '최근 거래 없음' : formatAmount(latestTrade.dealAmount);
}

function complexSummaryMeta(complex: ParcelComplexSummary | RegionComplexSummary): string {
  const values = [
    formatNumber(complex.unitCnt, '세대'),
    complex.useDate,
    formatAddress(complex.address),
  ].filter((value): value is string => value != null);

  return values.length === 0 ? '요약 정보 없음' : values.join(' · ');
}

function formatNumber(value: number | null, suffix: string): string | null {
  if (value == null) {
    return null;
  }

  return `${value.toLocaleString()}${suffix}`;
}

function complexScaleLabel(detail: ComplexDetail): string {
  const values = [
    formatNumber(detail.unitCnt, '세대'),
    formatNumber(detail.dongCnt, '개동'),
    detail.useDate == null ? null : `${detail.useDate.slice(0, 4)}년`,
  ].filter((value): value is string => value != null);
  return values.length === 0 ? '-' : values.join(' · ');
}

function TradeList({
  rows,
  totalElements,
  onLoadMore,
}: {
  rows: TradeItem[];
  totalElements: number;
  onLoadMore: () => void;
}) {
  const hasMore = rows.length < totalElements;
  return (
    <section className="trade-list" aria-label="거래 목록" data-detail-section="trade-history">
      <div className="trade-section-header">
        <h3>거래 내역</h3>
        {totalElements > 0 ? (
          <p>{rows.length.toLocaleString()} / {totalElements.toLocaleString()}건</p>
        ) : null}
      </div>
      {rows.length === 0 ? (
        <p>거래 내역이 없습니다</p>
      ) : (
        <>
          <table>
            <caption className="sr-only">선택한 단지 또는 필지의 실거래 목록</caption>
            <thead>
              <tr>
                <th scope="col">일자</th>
                <th scope="col">금액</th>
                <th scope="col">면적</th>
                <th scope="col">층</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((trade) => {
                const area = areaLabels(trade.exclArea);
                const location = tradeLocationLabels(trade);
                return (
                  <tr key={trade.tradeId}>
                    <td>{trade.dealDate}</td>
                    <td className="trade-amount-value" data-trade-cell="amount">
                      <span className="trade-amount-label">{formatAmount(trade.dealAmount)}</span>
                    </td>
                    <td className="trade-area-value" data-trade-cell="area">
                      <span>{area.squareMeters}</span>
                      <span className="trade-area-pyeong">{area.pyeong}</span>
                    </td>
                    <td className="trade-location-value" data-trade-cell="floor">
                      {location.building == null ? null : <span className="trade-building">{location.building}</span>}
                      {location.building == null ? null : ' '}
                      <span className="trade-floor">{location.floor}</span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {hasMore ? (
            <button
              type="button"
              className="trade-load-more"
              aria-label="거래 더 보기"
              onClick={onLoadMore}
            >
              더보기
            </button>
          ) : null}
        </>
      )}
    </section>
  );
}

function formatAmount(amount: number | null): string {
  if (amount == null) {
    return '최근 거래 없음';
  }

  if (amount < 10000) {
    return `${amount.toLocaleString()}만원`;
  }

  const eok = Math.floor(amount / 10000);
  const man = amount % 10000;
  return man === 0
    ? `${eok.toLocaleString()}억`
    : `${eok.toLocaleString()}억 ${man.toLocaleString()}만원`;
}

function compareTradesNewestFirst(first: TradeItem, second: TradeItem): number {
  return second.dealDate.localeCompare(first.dealDate) || second.tradeId - first.tradeId;
}

function tradeLocationLabels(trade: TradeItem): { building: string | null; floor: string } {
  const aptDong = trade.aptDong?.trim() || null;
  return {
    building: aptDong == null ? null : /^\d+$/.test(aptDong) ? `${aptDong}동` : aptDong,
    floor: trade.floor == null ? '층 정보 없음' : `${trade.floor}층`,
  };
}
