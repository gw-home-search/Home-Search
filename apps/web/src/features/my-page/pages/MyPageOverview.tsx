import { Link } from 'react-router-dom';

import type { CurrentUser } from '../../auth/authTypes';
import { FavoriteCollection } from '../components/FavoriteCollection';
import { useFavoriteCollection } from '../hooks/useFavoriteCollection';
import { ProfileAvatar } from '../MyPagePrimitives';
import { PROVIDER_LABELS } from '../providerLabels';

export function MyPageOverview({
  onExplore,
  onFavoriteSelect,
  user,
}: {
  onExplore(): void;
  onFavoriteSelect(complexId: number, trigger: HTMLElement): void;
  user: CurrentUser;
}) {
  const favorites = useFavoriteCollection(3);
  return (
    <div className="my-page-content">
      <section aria-labelledby="my-profile-title" className="my-profile-summary">
        <ProfileAvatar user={user} />
        <div>
          <h2 id="my-profile-title">{user.displayName}</h2>
          <p>{PROVIDER_LABELS[user.provider]} 계정</p>
        </div>
      </section>
      <Link className="my-favorite-summary-link" to="/my/favorites">
        <span>관심 단지</span>
        <span>
          {' '}
          {favorites.state.phase === 'loading'
            ? '확인 중'
            : `${favorites.state.totalElements.toLocaleString('ko-KR')}곳`}
          <span aria-hidden="true">›</span>
        </span>
      </Link>
      <section aria-labelledby="recent-favorite-title" className="my-section my-recent-section">
        <h2 className="my-section-title" id="recent-favorite-title">최근 저장한 단지</h2>
        <FavoriteCollection
          compact
          collection={favorites}
          emptyAction="단지 찾아보기"
          emptyDescription="지도에서 궁금한 단지를 저장해보세요."
          emptyTitle="아직 관심 단지가 없어요"
          onExplore={onExplore}
          onFavoriteSelect={onFavoriteSelect}
        />
      </section>
    </div>
  );
}
