import { lazy, Suspense } from 'react';

import { MapApp, type MapAppProps } from './MapApp';
import './App.css';

const CoordinateOverrideAdminPage = lazy(() =>
  import('../features/admin/CoordinateOverrideAdminPage').then((module) => ({
    default: module.CoordinateOverrideAdminPage,
  })));
const CoordinateReasonGuidePage = lazy(() =>
  import('../features/admin/CoordinateOverrideAdminPage').then((module) => ({
    default: module.CoordinateReasonGuidePage,
  })));
const MetadataAdminPage = lazy(() =>
  import('../features/admin/MetadataAdminPage').then((module) => ({
    default: module.MetadataAdminPage,
  })));

export function App(props: MapAppProps) {
  const adminRoute = resolveAdminRoute();
  if (adminRoute != null && !isAdminSurfaceEnabled()) {
    return <NotFoundPage />;
  }

  if (adminRoute == null) {
    return <MapApp {...props} />;
  }

  return (
    <Suspense fallback={<AdminRouteFallback />}>
      {adminRoute === 'coordinate' ? <CoordinateOverrideAdminPage /> : null}
      {adminRoute === 'coordinate-reasons' ? <CoordinateReasonGuidePage /> : null}
      {adminRoute === 'metadata' ? <MetadataAdminPage /> : null}
    </Suspense>
  );
}

function AdminRouteFallback() {
  return (
    <main className="admin-route-fallback" role="status" aria-live="polite">
      관리자 화면 불러오는 중
    </main>
  );
}

function NotFoundPage() {
  return (
    <main className="not-found-page">
      <h1>페이지를 찾을 수 없습니다</h1>
      <p>요청한 주소가 없거나 현재 화면에서 사용할 수 없습니다.</p>
      <a href="/" aria-label="지도로 돌아가기">지도로 돌아가기</a>
    </main>
  );
}

type AdminRoute = 'coordinate' | 'coordinate-reasons' | 'metadata';

function resolveAdminRoute(): AdminRoute | null {
  const path = window.location.pathname;
  if (path === '/admin/coordinates/reasons') {
    return 'coordinate-reasons';
  }
  if (path.startsWith('/admin/coordinates')) {
    return 'coordinate';
  }
  if (path.startsWith('/admin/metadata')) {
    return 'metadata';
  }
  return null;
}

function isAdminSurfaceEnabled(): boolean {
  return import.meta.env.VITE_APP_SURFACE === 'admin'
    || import.meta.env.VITE_ENABLE_ADMIN_SURFACE === 'true';
}
