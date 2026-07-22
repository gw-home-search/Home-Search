import { useEffect, useRef, type ReactNode } from 'react';
import { Navigate, NavLink, Route, Routes, useLocation } from 'react-router-dom';

import { AppHeader } from '../../app/AppHeader';
import { useAuth } from '../auth/AuthProvider';
import { AUTH_RETURN_TO_KEY } from '../auth/authReturnPath';
import { AccountPage } from './pages/AccountPage';
import { FavoriteListPage } from './pages/FavoriteListPage';
import { MyPageOverview } from './pages/MyPageOverview';

export function MyPageRoutes() {
  const auth = useAuth();
  const location = useLocation();
  const openedDialog = useRef(false);

  useEffect(() => {
    if (auth.status !== 'anonymous') {
      openedDialog.current = false;
      return;
    }
    window.sessionStorage.setItem(AUTH_RETURN_TO_KEY, safeMyPath(location.pathname));
    if (!openedDialog.current) {
      openedDialog.current = true;
      auth.openDialog();
    }
  }, [auth, location.pathname]);

  if (auth.status === 'checking') {
    return <MyPageStatus busy title="계정 정보를 확인하고 있어요" />;
  }
  if (auth.status === 'unavailable') {
    return (
      <MyPageStatus title="마이페이지를 불러오지 못했어요">
        <p>로그인 서비스 연결을 확인한 뒤 다시 시도해주세요.</p>
        <button onClick={() => void auth.retry()} type="button">다시 시도</button>
      </MyPageStatus>
    );
  }
  if (auth.status !== 'authenticated' || auth.currentUser == null) {
    return (
      <MyPageStatus title="로그인이 필요한 페이지예요">
        <p>관심 단지와 계정 정보를 확인하려면 로그인해주세요.</p>
        <button onClick={(event) => auth.openDialog(event.currentTarget)} type="button">
          로그인하고 계속하기
        </button>
      </MyPageStatus>
    );
  }

  return (
    <MyPageLayout>
      <Routes>
        <Route element={<MyPageOverview user={auth.currentUser} />} index />
        <Route element={<FavoriteListPage />} path="favorites" />
        <Route element={<AccountPage user={auth.currentUser} />} path="account" />
        <Route element={<Navigate replace to="/my" />} path="*" />
      </Routes>
    </MyPageLayout>
  );
}

function MyPageLayout({ children }: { children: ReactNode }) {
  return (
    <div className="my-page-shell" data-ui-surface="account">
      <AppHeader />
      <div className="my-page-frame">
        <nav aria-label="마이페이지 메뉴" className="my-page-nav">
          <NavLink end to="/my">개요</NavLink>
          <NavLink to="/my/favorites">관심 단지</NavLink>
          <NavLink to="/my/account">계정</NavLink>
        </nav>
        <main className="my-page-main">{children}</main>
      </div>
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
    <div className="my-page-shell" data-ui-surface="account">
      <AppHeader />
      <main aria-busy={busy || undefined} className="my-page-status">
        <div>
          <h1>{title}</h1>
          {children ?? <p>잠시만 기다려주세요.</p>}
        </div>
      </main>
    </div>
  );
}

function safeMyPath(pathname: string): string {
  return pathname === '/my/favorites' || pathname === '/my/account' ? pathname : '/my';
}
