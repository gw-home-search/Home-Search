import { createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';

import { createAuthClient, type AuthClient, type AuthRestoreResult } from './api/authClient';
import { AuthDialog } from './AuthDialog';
import { AUTH_MESSAGES, type AuthStatus, type CurrentUser, type OAuthProvider } from './authTypes';

type AuthContextValue = {
  authenticatedRequest(path: string, init?: RequestInit, target?: 'user' | 'public'): Promise<Response>;
  closeDialog(): void;
  currentUser: CurrentUser | null;
  dialogError: string | null;
  isDialogOpen: boolean;
  isLoggingOut: boolean;
  login(provider: OAuthProvider): void;
  logout(): Promise<void>;
  notice: string | null;
  openDialog(trigger?: HTMLElement): void;
  retry(): Promise<void>;
  status: AuthStatus;
};

type AuthProviderProps = {
  children: ReactNode;
  client?: AuthClient;
  navigate?: (url: string) => void;
};

const AuthContext = createContext<AuthContextValue | null>(null);
let defaultClient: AuthClient | null = null;

export function AuthProvider({ children, client, navigate = defaultNavigate }: AuthProviderProps) {
  const authClient = useMemo(() => client ?? getDefaultClient(), [client]);
  const [status, setStatus] = useState<AuthStatus>('checking');
  const [currentUser, setCurrentUser] = useState<CurrentUser | null>(null);
  const [isDialogOpen, setIsDialogOpen] = useState(false);
  const [dialogError, setDialogError] = useState<string | null>(null);
  const [connectingProvider, setConnectingProvider] = useState<OAuthProvider | null>(null);
  const [isLoggingOut, setIsLoggingOut] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const dialogTriggerRef = useRef<HTMLElement | null>(null);
  const mountedRef = useRef(false);
  const restoreStartedRef = useRef(false);

  const applyRestore = useCallback((result: AuthRestoreResult, callbackSuccess = false, degradeUnavailable = false) => {
    if (result.kind === 'authenticated') {
      setStatus('authenticated');
      setCurrentUser(result.currentUser);
      setDialogError(null);
      setIsDialogOpen(false);
      if (callbackSuccess) setNotice(AUTH_MESSAGES.loginSuccess);
      return;
    }
    setCurrentUser(null);
    if (result.kind === 'unavailable') {
      if (degradeUnavailable) {
        setStatus('anonymous');
        setDialogError(null);
        return;
      }
      setStatus('unavailable');
      setDialogError(AUTH_MESSAGES.serviceUnavailable);
      return;
    }
    setStatus('anonymous');
    if (result.kind === 'expired') {
      setDialogError(AUTH_MESSAGES.sessionExpired);
      setIsDialogOpen(true);
    }
  }, []);

  useEffect(() => {
    mountedRef.current = true;
    if (!restoreStartedRef.current) {
      restoreStartedRef.current = true;
      const callbackPath = window.location.pathname;
      if (callbackPath === '/auth/failure') {
        window.history.replaceState({}, '', '/');
        setStatus('anonymous');
        setDialogError(AUTH_MESSAGES.callbackFailure);
        setIsDialogOpen(true);
      } else {
        const callbackSuccess = callbackPath === '/auth/success';
        if (callbackSuccess) window.history.replaceState({}, '', '/');
        void authClient.restoreSession().then((result) => {
          if (mountedRef.current) applyRestore(result, callbackSuccess, true);
        });
      }
    }
    return () => {
      mountedRef.current = false;
    };
  }, [applyRestore, authClient]);

  useEffect(() => {
    if (notice == null) return;
    const timeout = window.setTimeout(() => setNotice(null), 3_000);
    return () => window.clearTimeout(timeout);
  }, [notice]);

  const openDialog = useCallback((trigger?: HTMLElement) => {
    dialogTriggerRef.current = trigger ?? null;
    setDialogError(status === 'unavailable' ? AUTH_MESSAGES.serviceUnavailable : null);
    setIsDialogOpen(true);
  }, [status]);

  const closeDialog = useCallback(() => {
    setIsDialogOpen(false);
    setConnectingProvider(null);
    queueMicrotask(() => dialogTriggerRef.current?.focus());
  }, []);

  const login = useCallback((provider: OAuthProvider) => {
    setConnectingProvider(provider);
    try {
      navigate(authClient.authorizationUrl(provider));
    } catch {
      setConnectingProvider(null);
      setStatus('unavailable');
      setDialogError(AUTH_MESSAGES.serviceUnavailable);
    }
  }, [authClient, navigate]);

  const retry = useCallback(async () => {
    setStatus('checking');
    setDialogError(null);
    const result = await authClient.restoreSession({ force: true });
    if (mountedRef.current) applyRestore(result);
  }, [applyRestore, authClient]);

  const logout = useCallback(async () => {
    setIsLoggingOut(true);
    setDialogError(null);
    try {
      await authClient.logout();
      setCurrentUser(null);
      setStatus('anonymous');
    } catch {
      setDialogError(AUTH_MESSAGES.logoutFailure);
    } finally {
      setCurrentUser(null);
      setStatus('anonymous');
      setIsLoggingOut(false);
    }
  }, [authClient]);

  const authenticatedRequest = useCallback(async (
    path: string,
    init?: RequestInit,
    target: 'user' | 'public' = 'user',
  ) => {
    const response = await authClient.authenticatedRequest(path, init, target);
    if (response.status === 401 && mountedRef.current) {
      setCurrentUser(null);
      setStatus('anonymous');
      setDialogError(AUTH_MESSAGES.sessionExpired);
      setIsDialogOpen(true);
    }
    return response;
  }, [authClient]);

  const value = useMemo<AuthContextValue>(() => ({
    authenticatedRequest,
    closeDialog,
    currentUser,
    dialogError,
    isDialogOpen,
    isLoggingOut,
    login,
    logout,
    notice,
    openDialog,
    retry,
    status,
  }), [authenticatedRequest, closeDialog, currentUser, dialogError, isDialogOpen, isLoggingOut, login, logout, notice, openDialog, retry, status]);

  return (
    <AuthContext.Provider value={value}>
      {children}
      <AuthDialog
        connectingProvider={connectingProvider}
        error={dialogError}
        isOpen={isDialogOpen}
        onClose={closeDialog}
        onProviderSelect={login}
        onRetry={() => void retry()}
      />
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (context == null) throw new Error('useAuth must be used inside AuthProvider');
  return context;
}

function getDefaultClient(): AuthClient {
  if (defaultClient == null) {
    try {
      defaultClient = createAuthClient();
    } catch {
      defaultClient = unavailableAuthClient();
    }
  }
  return defaultClient;
}

function unavailableAuthClient(): AuthClient {
  return {
    async authenticatedRequest() {
      throw new Error('Authentication required');
    },
    authorizationUrl() {
      throw new Error('User API is not configured');
    },
    async logout() {
      throw new Error('User API is not configured');
    },
    async restoreSession() {
      return { kind: 'unavailable' };
    },
  };
}

function defaultNavigate(url: string) {
  window.location.assign(url);
}
