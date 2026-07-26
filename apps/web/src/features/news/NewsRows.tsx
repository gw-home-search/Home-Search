import type { MarketNewsItem } from './api/fetchMarketNews';
import { categoryLabel } from './newsLabels';

const RELATION_LABELS = {
  DIRECT_COMPLEX: '단지 직접 언급',
  SAME_DONG: '같은 동',
  SAME_SIGUNGU: '같은 시군구',
} as const;

export function NewsRows({ items, showRelation = false }: {
  items: MarketNewsItem[];
  showRelation?: boolean;
}) {
  return (
    <ol className="market-news-list">
      {items.map((item) => (
        <li key={item.articleId}>
          <a
            aria-label={`${item.title} 원문 새 창 열기`}
            href={item.url}
            rel="noopener noreferrer"
            target="_blank"
          >
            <span className="market-news-row-meta">
              {showRelation && item.relationType ? (
                <strong>{RELATION_LABELS[item.relationType]}</strong>
              ) : null}
              <span>{categoryLabel(item.category)}</span>
            </span>
            <span className="market-news-row-title" aria-hidden="true">{item.title}</span>
            <span className="market-news-row-footer">
              <span>{item.region?.name ?? '전국'}</span>
              <time dateTime={item.providedAt}>뉴스 제공 {formatDate(item.providedAt)}</time>
              <span aria-hidden="true">↗</span>
            </span>
          </a>
        </li>
      ))}
    </ol>
  );
}

function formatDate(value: string): string {
  const parts = new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    month: 'numeric',
    day: 'numeric',
  }).formatToParts(new Date(value));
  const month = parts.find((part) => part.type === 'month')?.value ?? '';
  const day = parts.find((part) => part.type === 'day')?.value ?? '';
  return `${month}.${day}`;
}
