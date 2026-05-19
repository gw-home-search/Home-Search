export function resolveApiUrl(path: string): string {
  const baseUrl = import.meta.env.VITE_API_SERVER_IP as string | undefined;
  if (!baseUrl) {
    return path;
  }

  return new URL(path, baseUrl).toString();
}
