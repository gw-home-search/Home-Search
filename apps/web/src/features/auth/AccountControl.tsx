import { useEffect, useId, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';

import { ChevronDownIcon } from '../../shared/icons';
import type { CurrentUser, OAuthProvider } from './authTypes';
import { useAuth } from './AuthProvider';

const PROVIDER_LABELS: Record<OAuthProvider, string> = {
  google: 'Google',
  kakao: 'Kakao',
  naver: 'Naver',
};

export function AccountControl() {
  const auth = useAuth();
  const navigate = useNavigate();
  const [isMenuOpen, setIsMenuOpen] = useState(false);
  const controlRef = useRef<HTMLDivElement>(null);
  const chipRef = useRef<HTMLButtonElement>(null);
  const menuId = `account-menu-${useId().replace(/:/g, '')}`;

  useEffect(() => {
    if (!isMenuOpen) return;
    function handlePointerDown(event: PointerEvent) {
      if (!controlRef.current?.contains(event.target as Node)) setIsMenuOpen(false);
    }
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key !== 'Escape') return;
      setIsMenuOpen(false);
      queueMicrotask(() => chipRef.current?.focus());
    }
    document.addEventListener('pointerdown', handlePointerDown);
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('pointerdown', handlePointerDown);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [isMenuOpen]);

  useEffect(() => {
    if (auth.status !== 'authenticated') setIsMenuOpen(false);
  }, [auth.status]);

  return (
    <div className="account-control" data-auth-status={auth.status} ref={controlRef}>
      {auth.status === 'checking' ? <span aria-hidden="true" className="account-login-placeholder" /> : null}
      {auth.status === 'anonymous' || auth.status === 'unavailable' ? (
        <button
          className="account-login-button"
          onClick={(event) => auth.openDialog(event.currentTarget)}
          type="button"
        >
          <LoginIcon />
          <span>로그인</span>
        </button>
      ) : null}
      {auth.status === 'authenticated' && auth.currentUser != null ? (
        <>
          <button
            aria-controls={menuId}
            aria-expanded={isMenuOpen}
            aria-haspopup="menu"
            aria-label={`${auth.currentUser.displayName} 계정 메뉴`}
            className="account-chip"
            disabled={auth.isLoggingOut}
            onClick={() => setIsMenuOpen((open) => !open)}
            ref={chipRef}
            type="button"
          >
            <UserAvatar user={auth.currentUser} />
            <span className="account-chip-copy">
              <span>{auth.currentUser.displayName}</span>
            </span>
            <ChevronDownIcon aria-hidden="true" className="account-chip-chevron" />
          </button>
          {isMenuOpen ? (
            <div aria-label="계정 메뉴" className="account-menu" id={menuId} role="menu">
              <div className="account-menu-user">
                <strong>{auth.currentUser.displayName}</strong>
                <span>{PROVIDER_LABELS[auth.currentUser.provider]} 계정</span>
              </div>
              <Link onClick={() => setIsMenuOpen(false)} role="menuitem" to="/my">
                마이페이지
              </Link>
              <Link onClick={() => setIsMenuOpen(false)} role="menuitem" to="/my/favorites">
                관심 단지
              </Link>
              <button
                disabled={auth.isLoggingOut}
                onClick={() => void auth.logout().then(() => navigate('/'))}
                role="menuitem"
                type="button"
              >
                {auth.isLoggingOut ? '로그아웃 중...' : '로그아웃'}
              </button>
              {auth.dialogError != null ? <p className="account-menu-error" role="alert">{auth.dialogError}</p> : null}
            </div>
          ) : null}
        </>
      ) : null}
      <div aria-live="polite" className="auth-notice" role="status">
        {auth.notice}
      </div>
    </div>
  );
}

function LoginIcon() {
  return (
    <svg aria-hidden="true" className="account-login-icon" viewBox="0 0 24 24">
      <circle cx="12" cy="8" fill="none" r="3.25" />
      <path d="M5.75 19c.6-3.28 2.68-5 6.25-5s5.65 1.72 6.25 5" fill="none" />
    </svg>
  );
}

function UserAvatar({ user }: { user: CurrentUser }) {
  const [imageFailed, setImageFailed] = useState(false);
  useEffect(() => setImageFailed(false), [user.profileImage]);
  const initial = Array.from(user.displayName.trim())[0] ?? '홈';
  if (user.profileImage != null && !imageFailed) {
    return <img alt="" className="account-avatar" onError={() => setImageFailed(true)} src={user.profileImage} />;
  }
  return <span aria-hidden="true" className="account-avatar account-avatar-fallback">{initial}</span>;
}
