import { useCallback } from 'react';
import { BrowserRouter, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';

import { MapApp, type MapAppProps } from './MapApp';
import { AuthProvider } from '../features/auth/AuthProvider';
import type { AuthClient } from '../features/auth/api/authClient';
import { readInsightMetric } from '../features/insights/insightMetricConfig';
import './App.css';

export type AppProps = MapAppProps & {
  authClient?: AuthClient;
  authNavigate?: (url: string) => void;
};

export function App({ authClient, authNavigate, ...mapProps }: AppProps) {
  return (
    <BrowserRouter>
      <RoutedApp authClient={authClient} authNavigate={authNavigate} mapProps={mapProps} />
    </BrowserRouter>
  );
}

function RoutedApp({
  authClient,
  authNavigate,
  mapProps,
}: Pick<AppProps, 'authClient' | 'authNavigate'> & { mapProps: MapAppProps }) {
  const navigate = useNavigate();
  const replaceRoute = useCallback((path: string) => navigate(path, { replace: true }), [navigate]);

  return (
    <AuthProvider
      client={authClient}
      navigate={authNavigate}
      replaceRoute={replaceRoute}
    >
      <Routes>
        <Route element={<AuthCallbackPage />} path="/auth/success" />
        <Route element={<AuthCallbackPage />} path="/auth/failure" />
        <Route element={<MapRoute mapProps={mapProps} />} path="*" />
      </Routes>
    </AuthProvider>
  );
}

function MapRoute({ mapProps }: { mapProps: MapAppProps }) {
  const location = useLocation();
  if (location.pathname !== '/' && location.pathname !== '/insights' && !isMyPagePath(location.pathname)) {
    return <Navigate replace to="/" />;
  }
  if (location.pathname === '/insights') {
    const params = new URLSearchParams(location.search);
    const metric = readInsightMetric(location.search);
    const needsDefaultScope = !params.has('scope');
    const needsMetricNormalization = params.get('metric') !== metric;
    if (needsDefaultScope || needsMetricNormalization) {
      params.set('metric', metric);
      if (needsDefaultScope) {
        params.set('scope', 'SIDO');
        params.set('regionCode', '11');
      }
      return <Navigate replace to={`/insights?${params.toString()}`} />;
    }
  }
  return <MapApp {...mapProps} />;
}

function isMyPagePath(pathname: string): boolean {
  return pathname === '/my' || pathname.startsWith('/my/');
}

function AuthCallbackPage() {
  return (
    <main aria-busy="true" className="auth-callback-page">
      <p>로그인 상태를 확인하고 있어요.</p>
    </main>
  );
}
