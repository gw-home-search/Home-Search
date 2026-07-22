import type { CurrentUser } from '../../auth/authTypes';
import { ProfileAvatar } from '../MyPagePrimitives';
import { PROVIDER_LABELS } from '../providerLabels';

export function AccountPage({ user }: { user: CurrentUser }) {
  return (
    <div className="my-page-content">
      <section aria-labelledby="account-profile-title" className="account-profile-section">
        <div className="account-profile-heading">
          <ProfileAvatar user={user} />
          <div>
            <h2 id="account-profile-title">{user.displayName}</h2>
            <p>{PROVIDER_LABELS[user.provider]} 계정</p>
          </div>
        </div>
        <h2 className="account-section-title">계정 정보</h2>
        <dl className="account-readonly-list">
          <div><dt>표시 이름</dt><dd>{user.displayName}</dd></div>
          <div><dt>연결 계정</dt><dd>{PROVIDER_LABELS[user.provider]}</dd></div>
        </dl>
      </section>
    </div>
  );
}
