import { FavoriteCollection } from '../components/FavoriteCollection';
import { useFavoriteCollection } from '../hooks/useFavoriteCollection';
import { getUserFeedback } from '../../../shared/feedback/feedbackCatalog';

export function FavoriteListPage({
  onExplore,
  onFavoriteSelect,
}: {
  onExplore(): void;
  onFavoriteSelect(complexId: number, trigger: HTMLElement): void;
}) {
  const favorites = useFavoriteCollection(20, true);
  return (
    <div className="my-page-content">
      <h2 className="my-visually-hidden">관심 단지</h2>
      {favorites.state.phase !== 'loading' && favorites.state.phase !== 'error' ? (
        <p className="my-list-count">총 {favorites.state.totalElements.toLocaleString('ko-KR')}곳</p>
      ) : null}
      <FavoriteCollection
        collection={favorites}
        emptyAction="단지 찾아보기"
        emptyDescription="지도에서 궁금한 단지를 저장해보세요."
        emptyTitle="아직 관심 단지가 없어요"
        onExplore={onExplore}
        onFavoriteSelect={onFavoriteSelect}
      />
      {favorites.hasMore ? (
        <div className="my-load-more">
          {favorites.loadMoreError ? (
            <button onClick={favorites.retryLoadMore} type="button">
              {getUserFeedback('FAVORITES_MORE_UNAVAILABLE').actionLabel}
            </button>
          ) : (
            <button disabled={favorites.loadingMore} onClick={favorites.loadMore} type="button">
              {favorites.loadingMore ? '불러오는 중' : '더 보기'}
            </button>
          )}
        </div>
      ) : null}
    </div>
  );
}
