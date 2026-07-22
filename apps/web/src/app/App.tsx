import { useCallback } from 'react';
import { BrowserRouter, Navigate, Route, Routes, useNavigate } from 'react-router-dom';

import { MapApp, type MapAppProps } from './MapApp';
import { AuthProvider } from '../features/auth/AuthProvider';
import type { AuthClient } from '../features/auth/api/authClient';
import { MyPageRoutes } from '../features/my-page/MyPageRoutes';
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
        <Route element={<MapApp {...mapProps} />} path="/" />
        <Route element={<MyPageRoutes />} path="/my/*" />
        <Route element={<AuthCallbackPage />} path="/auth/success" />
        <Route element={<AuthCallbackPage />} path="/auth/failure" />
        <Route element={<Navigate replace to="/" />} path="*" />
      </Routes>
    </AuthProvider>
  );
}

function AuthCallbackPage() {
  return (
    <main aria-busy="true" className="auth-callback-page">
      <p>로그인 상태를 확인하고 있어요.</p>
    </main>
  );
}
