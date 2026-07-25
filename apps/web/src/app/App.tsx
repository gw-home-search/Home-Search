import { useCallback } from 'react';
import { BrowserRouter, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';

import { MapApp, type MapAppProps } from './MapApp';
import { AuthProvider } from '../features/auth/AuthProvider';
import type { AuthClient } from '../features/auth/api/authClient';
import { readInsightMetric } from '../features/insights/insightMetricConfig';
import { MARKET_NEWS_ENABLED } from '../features/news/newsFeature';
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
  if (location.pathname === '/insights/news' && !MARKET_NEWS_ENABLED) {
    return <Navigate replace to="/" />;
  }
  if (
    location.pathname !== '/'
    && location.pathname !== '/insights'
    && location.pathname !== '/insights/news'
    && !isMyPagePath(location.pathname)
  ) {
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
  if (location.pathname === '/insights/news') {
    const params = new URLSearchParams(location.search);
    const requestedScope = params.get('scope');
    const scope = requestedScope === 'NATIONWIDE' ? 'NATIONWIDE' : 'SIDO';
    const validCategories = new Set([
      'ALL', 'POLICY', 'FINANCE_LOAN', 'SUPPLY_SALE', 'REDEVELOPMENT',
      'TRANSACTION_PRICE', 'TRANSPORT_DEVELOPMENT',
    ]);
    const category = validCategories.has(params.get('category') ?? '') ? params.get('category')! : 'ALL';
    const regionCode = params.get('regionCode');
    const needsScopeNormalization = requestedScope !== 'NATIONWIDE' && requestedScope !== 'SIDO';
    const needsRegionNormalization = scope === 'NATIONWIDE'
      ? params.has('regionCode')
      : regionCode == null || !/^[0-9]{2}$/u.test(regionCode);
    if (
      needsScopeNormalization
      || !validCategories.has(params.get('category') ?? '')
      || needsRegionNormalization
    ) {
      params.set('scope', scope);
      params.set('category', category);
      if (scope === 'SIDO' && needsRegionNormalization) params.set('regionCode', '11');
      if (scope === 'NATIONWIDE') params.delete('regionCode');
      return <Navigate replace to={`/insights/news?${params.toString()}`} />;
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
