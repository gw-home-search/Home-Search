const LOCAL_USER_API_URL = 'http://localhost:8082';

export function resolveUserApiUrl(
  configuredUrl = import.meta.env.VITE_USER_API_SERVER_IP,
  mode = import.meta.env.MODE,
  browserOrigin = typeof window === 'undefined' ? undefined : window.location.origin,
): string {
  let candidate = configuredUrl?.trim();
  if (!candidate) {
    if (mode === 'development' || mode === 'test') return LOCAL_USER_API_URL;
    candidate = browserOrigin?.trim();
    if (!candidate) throw new Error('browser origin is required outside local/test');
  }

  const url = new URL(candidate);
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new Error('VITE_USER_API_SERVER_IP must use http or https');
  }
  if (url.username || url.password || url.search || url.hash || (url.pathname !== '/' && url.pathname !== '')) {
    throw new Error('VITE_USER_API_SERVER_IP must be an origin without credentials, path, query, or fragment');
  }

  return url.origin;
}
