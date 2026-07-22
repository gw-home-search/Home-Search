import { useState } from 'react';

import type { CurrentUser } from '../auth/authTypes';

export function PageHeading({ description, title }: { description: string; title: string }) {
  return (
    <header className="my-page-heading">
      <h1>{title}</h1>
      <p>{description}</p>
    </header>
  );
}

export function ProfileAvatar({ user }: { user: CurrentUser }) {
  const [failed, setFailed] = useState(false);
  const initial = Array.from(user.displayName.trim())[0] ?? '홈';
  if (user.profileImage && !failed) {
    return <img alt="" className="my-profile-avatar" onError={() => setFailed(true)} src={user.profileImage} />;
  }
  return <span aria-hidden="true" className="my-profile-avatar my-profile-avatar-fallback">{initial}</span>;
}
