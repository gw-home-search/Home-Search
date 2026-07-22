import { useState } from 'react';

import { FavoriteCollection } from '../components/FavoriteCollection';
import { useFavoriteCollection } from '../hooks/useFavoriteCollection';
import { PageHeading } from '../MyPagePrimitives';

export function FavoriteListPage() {
  const [page, setPage] = useState(0);
  const favorites = useFavoriteCollection(page, 20);
  return (
    <div className="my-page-content">
      <PageHeading description="궁금한 단지를 다시 찾기 쉽게 모아두었어요." title="관심 단지" />
      {favorites.state.phase !== 'loading' && favorites.state.phase !== 'error' ? (
        <p className="my-list-count">총 {favorites.state.totalElements.toLocaleString('ko-KR')}곳</p>
      ) : null}
      <FavoriteCollection
        collection={favorites}
        emptyAction="단지 찾아보기"
        emptyDescription="지도에서 궁금한 단지를 저장해보세요."
        emptyTitle="아직 관심 단지가 없어요"
      />
      {favorites.state.totalPages > 1 ? (
        <nav aria-label="관심 단지 페이지" className="my-pagination">
          <button disabled={page === 0} onClick={() => setPage((value) => value - 1)} type="button">이전</button>
          <span>{page + 1} / {favorites.state.totalPages}</span>
          <button
            disabled={page + 1 >= favorites.state.totalPages}
            onClick={() => setPage((value) => value + 1)}
            type="button"
          >다음</button>
        </nav>
      ) : null}
    </div>
  );
}
