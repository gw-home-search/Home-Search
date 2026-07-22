export const AUTH_RETURN_TO_KEY = 'home-search:return-to';

export function readSafeAuthReturnTo(): string | null {
  const value = window.sessionStorage.getItem(AUTH_RETURN_TO_KEY);
  return value === '/my' || value === '/my/favorites' || value === '/my/account' ? value : null;
}
