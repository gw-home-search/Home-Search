import type { FavoriteCollectionItem } from '../hooks/useFavoriteCollection';
import { useFavoriteCollection } from '../hooks/useFavoriteCollection';
import { getUserFeedback } from '../../../shared/feedback/feedbackCatalog';
import { RequestStateNotice } from '../../../shared/RequestStateNotice';

export function FavoriteCollection({
  collection,
  compact = false,
  emptyAction,
  emptyDescription,
  emptyTitle,
  onExplore,
  onFavoriteSelect,
}: {
  collection: ReturnType<typeof useFavoriteCollection>;
  compact?: boolean;
  emptyAction: string;
  emptyDescription: string;
  emptyTitle: string;
  onExplore(): void;
  onFavoriteSelect(complexId: number, trigger: HTMLElement): void;
}) {
  const { state } = collection;
  if (state.phase === 'loading') return <FavoriteSkeleton compact={compact} />;
  if (state.phase === 'error') {
    return (
      <RequestStateNotice
        state="error"
        loadingMessage=""
        emptyMessage=""
        feedback={getUserFeedback('FAVORITES_UNAVAILABLE')}
        onRetry={collection.retry}
      />
    );
  }
  if (state.items.length === 0) {
    return (
      <div className="my-local-state my-empty-state">
        <strong>{emptyTitle}</strong>
        <p>{emptyDescription}</p>
        <button onClick={onExplore} type="button">{emptyAction}</button>
      </div>
    );
  }
  return (
    <>
      {collection.refreshFeedback == null ? null : (
        <RequestStateNotice
          state="error"
          loadingMessage=""
          emptyMessage=""
          feedback={getUserFeedback(collection.refreshFeedback)}
          onRetry={collection.retry}
        />
      )}
      <ul className="favorite-collection" data-compact={compact || undefined}>
        {state.items.map((item) => (
          <FavoriteRow
            compact={compact}
            item={item}
            key={item.complexId}
            onFavoriteSelect={onFavoriteSelect}
            onRemove={() => void collection.remove(item.complexId)}
            onRetry={() => void collection.retryDetail(item.complexId)}
          />
        ))}
      </ul>
      <p aria-live="polite" className="my-live-message" role="status">{collection.liveMessage}</p>
    </>
  );
}

function FavoriteRow({
  compact,
  item,
  onRemove,
  onRetry,
  onFavoriteSelect,
}: {
  compact: boolean;
  item: FavoriteCollectionItem;
  onRemove(): void;
  onRetry(): void;
  onFavoriteSelect(complexId: number, trigger: HTMLElement): void;
}) {
  const displayName = favoriteName(item);
  const metadata = favoriteMetadata(item);
  return (
    <li className="favorite-row" data-detail-phase={item.detailPhase}>
      <button
        aria-label={`${displayName} 지도에서 보기`}
        className="favorite-row-main"
        onClick={(event) => onFavoriteSelect(item.complexId, event.currentTarget)}
        type="button"
      >
        <span className="favorite-row-title">{displayName}</span>
        {item.detailPhase === 'loading' ? <span className="favorite-row-loading">단지 정보를 불러오는 중</span> : null}
        {item.detail?.address ? <span className="favorite-row-address">{item.detail.address}</span> : null}
        {!compact && metadata.length > 0 ? (
          <span className="favorite-row-meta">{metadata.map((value) => <span key={value}>{value}</span>)}</span>
        ) : null}
        <span className="favorite-row-saved">
          <time dateTime={item.savedAt}>{formatSavedAt(item.savedAt)}</time>
          {compact ? ' 관심 단지에 추가했어요' : ' 저장'}
        </span>
      </button>
      <div className="favorite-row-actions">
        {item.detailPhase === 'error' ? (
          <button className="favorite-retry" onClick={onRetry} type="button">
            {getUserFeedback('FAVORITE_DETAIL_UNAVAILABLE').actionLabel}
          </button>
        ) : null}
        <button
          aria-label={`${displayName} 관심 해제`}
          className="favorite-remove"
          disabled={item.mutationPhase === 'removing'}
          onClick={onRemove}
          type="button"
        >
          <HeartIcon filled />
          <span className="my-visually-hidden">{item.mutationPhase === 'removing' ? '해제 중' : '관심 해제'}</span>
        </button>
      </div>
      {item.mutationError ? (
        <p className="favorite-row-error" role="alert">
          {getUserFeedback(item.mutationError).title}
          <button type="button" onClick={onRemove}>
            {getUserFeedback(item.mutationError).actionLabel}
          </button>
        </p>
      ) : null}
    </li>
  );
}

function FavoriteSkeleton({ compact }: { compact: boolean }) {
  return (
    <div aria-label="관심 단지 불러오는 중" className="favorite-skeleton" data-compact={compact || undefined} role="status">
      {Array.from({ length: 3 }, (_, index) => <span aria-hidden="true" key={index} />)}
    </div>
  );
}

function HeartIcon({ filled = false }: { filled?: boolean }) {
  return (
    <svg aria-hidden="true" className="my-heart-icon" viewBox="0 0 24 24">
      <path d="M20.8 5.7a5.2 5.2 0 0 0-7.4 0L12 7.1l-1.4-1.4a5.2 5.2 0 0 0-7.4 7.4L12 21l8.8-7.9a5.2 5.2 0 0 0 0-7.4Z" fill={filled ? 'currentColor' : 'none'} />
    </svg>
  );
}

function favoriteName(item: FavoriteCollectionItem): string {
  return item.detail?.displayName?.trim() || item.detail?.tradeName?.trim() || item.detail?.name.trim() || `단지 #${item.complexId}`;
}

function favoriteMetadata(item: FavoriteCollectionItem): string[] {
  if (!item.detail) return [];
  const values: string[] = [];
  if (item.detail.unitCnt != null) values.push(`${item.detail.unitCnt.toLocaleString('ko-KR')}세대`);
  if (item.detail.dongCnt != null) values.push(`${item.detail.dongCnt.toLocaleString('ko-KR')}개 동`);
  if (values.length < 2 && item.detail.useDate) values.push(`${item.detail.useDate.slice(0, 4)}년 사용승인`);
  return values.slice(0, 2);
}

function formatSavedAt(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '저장 날짜 확인 불가';
  return new Intl.DateTimeFormat('ko-KR', { month: 'long', day: 'numeric' }).format(date);
}
