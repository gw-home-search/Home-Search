import { useNavigate } from 'react-router-dom';

import { useAuth } from '../../auth/AuthProvider';
import type { CurrentUser } from '../../auth/authTypes';
import { PageHeading, ProfileAvatar } from '../MyPagePrimitives';
import { PROVIDER_LABELS } from '../providerLabels';

export function AccountPage({ user }: { user: CurrentUser }) {
  const auth = useAuth();
  const navigate = useNavigate();
  return (
    <div className="my-page-content">
      <PageHeading description="현재 연결된 계정 정보를 확인하세요." title="계정" />
      <section aria-labelledby="account-profile-title" className="account-profile-section">
        <div className="account-profile-heading">
          <ProfileAvatar user={user} />
          <div>
            <h2 id="account-profile-title">{user.displayName}</h2>
            <p>{PROVIDER_LABELS[user.provider]} 계정으로 연결됨</p>
          </div>
        </div>
        <dl className="account-readonly-list">
          <div><dt>표시 이름</dt><dd>{user.displayName}</dd></div>
          <div><dt>로그인 계정</dt><dd>{PROVIDER_LABELS[user.provider]}</dd></div>
        </dl>
        <p className="account-readonly-note">개인정보 수정은 현재 지원하지 않습니다.</p>
        <button
          className="account-logout-action"
          disabled={auth.isLoggingOut}
          onClick={() => void auth.logout().then(() => navigate('/'))}
          type="button"
        >
          {auth.isLoggingOut ? '로그아웃 중...' : '로그아웃'}
        </button>
      </section>
    </div>
  );
}
