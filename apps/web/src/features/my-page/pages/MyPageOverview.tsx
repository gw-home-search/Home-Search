import { Link } from 'react-router-dom';

import type { CurrentUser } from '../../auth/authTypes';
import { FavoriteCollection } from '../components/FavoriteCollection';
import { useFavoriteCollection } from '../hooks/useFavoriteCollection';
import { PageHeading, ProfileAvatar } from '../MyPagePrimitives';
import { PROVIDER_LABELS } from '../providerLabels';

export function MyPageOverview({ user }: { user: CurrentUser }) {
  const favorites = useFavoriteCollection(0, 3);
  return (
    <div className="my-page-content">
      <PageHeading description="저장한 단지와 계정 정보를 한곳에서 확인하세요." title="마이페이지" />
      <section aria-labelledby="my-profile-title" className="my-profile-summary">
        <ProfileAvatar user={user} />
        <div>
          <h2 id="my-profile-title">{user.displayName}</h2>
          <p>{PROVIDER_LABELS[user.provider]} 계정</p>
        </div>
      </section>
      <section aria-labelledby="favorite-summary-title" className="my-section">
        <div className="my-section-heading">
          <div>
            <p className="my-section-kicker">관심 단지</p>
            <h2 id="favorite-summary-title">
              {favorites.state.phase === 'loading'
                ? '관심 단지를 확인하고 있어요'
                : `관심 단지 ${favorites.state.totalElements.toLocaleString('ko-KR')}곳`}
            </h2>
          </div>
          <Link to="/my/favorites">전체 보기</Link>
        </div>
      </section>
      <section aria-labelledby="recent-favorite-title" className="my-section my-recent-section">
        <div className="my-section-heading">
          <div>
            <p className="my-section-kicker">최근 활동</p>
            <h2 id="recent-favorite-title">최근 관심 활동</h2>
          </div>
        </div>
        <FavoriteCollection
          compact
          collection={favorites}
          emptyAction="단지 찾아보기"
          emptyDescription="지도에서 궁금한 단지를 저장해보세요."
          emptyTitle="아직 관심 단지가 없어요"
        />
      </section>
    </div>
  );
}
