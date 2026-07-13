import { MapApp, type MapAppProps } from './MapApp';
import { AuthProvider } from '../features/auth/AuthProvider';
import type { AuthClient } from '../features/auth/api/authClient';
import './App.css';

export type AppProps = MapAppProps & {
  authClient?: AuthClient;
  authNavigate?: (url: string) => void;
};

export function App({ authClient, authNavigate, ...mapProps }: AppProps) {
  return (
    <AuthProvider client={authClient} navigate={authNavigate}>
      <MapApp {...mapProps} />
    </AuthProvider>
  );
}
