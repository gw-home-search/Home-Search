const LOCAL_DEV_API_BASE_URL = 'http://localhost:8080';
const URL_PROTOCOL_PATTERN = /^[a-z][a-z\d+\-.]*:\/\//i;

export function resolveApiUrl(path: string): string {
  const baseUrl = configuredApiBaseUrl();
  if (!baseUrl) {
    return path;
  }

  return new URL(path, withDefaultProtocol(baseUrl)).toString();
}

function configuredApiBaseUrl(): string | null {
  const configuredBaseUrl = (import.meta.env.VITE_API_SERVER_IP as string | undefined)?.trim();
  if (configuredBaseUrl) {
    return configuredBaseUrl;
  }

  if (import.meta.env.DEV || import.meta.env.MODE === 'test') {
    return LOCAL_DEV_API_BASE_URL;
  }

  return null;
}

function withDefaultProtocol(baseUrl: string): string {
  if (URL_PROTOCOL_PATTERN.test(baseUrl)) {
    return baseUrl;
  }

  return `http://${baseUrl}`;
}
