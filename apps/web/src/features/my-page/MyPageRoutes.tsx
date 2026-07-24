import { useEffect, type ReactNode } from 'react';
import { Navigate, NavLink, Route, Routes, useLocation } from 'react-router-dom';

import { CloseIcon } from '../../shared/icons';
import { useAuth } from '../auth/AuthProvider';
import { AUTH_RETURN_TO_KEY } from '../auth/authReturnPath';
import { AccountPage } from './pages/AccountPage';
import { FavoriteListPage } from './pages/FavoriteListPage';
import { MyPageOverview } from './pages/MyPageOverview';
import { getUserFeedback } from '../../shared/feedback/feedbackCatalog';

export function MyPagePanel({
  hidden,
  onClose,
  onExplore,
  onFavoriteSelect,
}: {
  hidden: boolean;
  onClose(): void;
  onExplore(): void;
  onFavoriteSelect(complexId: number, trigger: HTMLElement): void;
}) {
  const auth = useAuth();
  const location = useLocation();
  useEffect(() => {
    if (auth.status === 'anonymous') {
      window.sessionStorage.setItem(AUTH_RETURN_TO_KEY, safeMyPath(location.pathname));
    }
  }, [auth.status, location.pathname]);

  return (
    <aside
      aria-hidden={hidden}
      aria-label="마이페이지 패널"
      className="my-page-panel"
      data-my-page-section={myPageSection(location.pathname)}
      hidden={hidden}
    >
      <header className="my-page-toolbar">
        <h2>마이페이지</h2>
        <button aria-label="마이페이지 닫기" onClick={onClose} type="button">
          <CloseIcon aria-hidden="true" />
        </button>
      </header>
      <nav aria-label="마이페이지" className="my-page-route-nav">
        <NavLink end to="/my">개요</NavLink>
        <NavLink to="/my/favorites">관심 단지</NavLink>
        <NavLink to="/my/account">계정</NavLink>
      </nav>
      <div className="my-page-scroll">
        {auth.status === 'checking' ? <MyPageSkeleton /> : null}
        {auth.status === 'unavailable' ? (
          <MyPageStatus title={getUserFeedback('AUTH_UNAVAILABLE').title}>
            <p>{getUserFeedback('AUTH_UNAVAILABLE').description}</p>
            <button onClick={() => void auth.retry()} type="button">
              {getUserFeedback('AUTH_UNAVAILABLE').actionLabel}
            </button>
          </MyPageStatus>
        ) : null}
        {auth.status === 'anonymous' ? (
          <MyPageStatus title="로그인이 필요한 페이지예요">
            <p>로그인하면 저장한 단지를 지도와 함께 확인할 수 있어요.</p>
            <button onClick={(event) => auth.openDialog(event.currentTarget)} type="button">
              로그인하기
            </button>
          </MyPageStatus>
        ) : null}
        {auth.status === 'authenticated' && auth.currentUser != null ? (
          <Routes key={auth.currentUser.userId}>
            <Route element={<MyPageOverview onExplore={onExplore} onFavoriteSelect={onFavoriteSelect} user={auth.currentUser} />} path="/my" />
            <Route element={<FavoriteListPage onExplore={onExplore} onFavoriteSelect={onFavoriteSelect} />} path="/my/favorites" />
            <Route element={<AccountPage user={auth.currentUser} />} path="/my/account" />
            <Route element={<Navigate replace to="/my" />} path="*" />
          </Routes>
        ) : null}
      </div>
    </aside>
  );
}

function MyPageSkeleton() {
  return (
    <div aria-label="마이페이지 불러오는 중" aria-live="polite" className="my-page-loading" role="status">
      <span className="my-page-loading-profile" />
      <span />
      <span />
      <span />
    </div>
  );
}

function MyPageStatus({
  busy = false,
  children,
  title,
}: {
  busy?: boolean;
  children?: ReactNode;
  title: string;
}) {
  return (
    <section aria-busy={busy || undefined} className="my-page-status">
      <div>
        <h2>{title}</h2>
        {children ?? <p>잠시만 기다려주세요.</p>}
      </div>
    </section>
  );
}

function safeMyPath(pathname: string): string {
  return pathname === '/my/favorites' || pathname === '/my/account' ? pathname : '/my';
}

function myPageSection(pathname: string): 'overview' | 'favorites' | 'account' {
  if (pathname === '/my/favorites') return 'favorites';
  if (pathname === '/my/account') return 'account';
  return 'overview';
}
