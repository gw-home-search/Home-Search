export type AdminPrincipal = { accountId: string; loginId: string; displayName: string; roles: string[]; permissions: string[] };
export class AdminHttpError extends Error { constructor(readonly status: number, message: string, readonly requestId?: string) { super(message); } }
export async function adminRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = init.method?.toUpperCase() ?? 'GET'; const headers = new Headers(init.headers);
  if (init.body) headers.set('Content-Type', 'application/json');
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) { const token = readCookie('XSRF-TOKEN'); if (token) headers.set('X-XSRF-TOKEN', decodeURIComponent(token)); }
  const response = await fetch(path, { ...init, headers, credentials: 'same-origin' });
  if (!response.ok) {
    if (response.status === 401) window.dispatchEvent(new Event('admin-session-expired'));
    const body = await safeProblem(response);
    throw new AdminHttpError(response.status, body.detail ?? statusMessage(response.status), response.headers.get('X-Request-Id') ?? undefined);
  }
  if (response.status === 204) return undefined as T; return response.json() as Promise<T>;
}
function readCookie(name: string) { return document.cookie.split('; ').find(v => v.startsWith(`${name}=`))?.slice(name.length + 1); }
async function safeProblem(response: Response): Promise<{detail?: string}> { try { const value: unknown = await response.json(); return typeof value === 'object' && value !== null && 'detail' in value && typeof value.detail === 'string' ? { detail: value.detail } : {}; } catch { return {}; } }
function statusMessage(status: number) { if (status === 401) return '세션이 만료되었습니다.'; if (status === 403) return '권한 또는 CSRF 검증에 실패했습니다.'; if (status === 409) return '다른 변경과 충돌했습니다.'; if (status >= 500) return '서버 요청에 실패했습니다.'; return '요청을 처리하지 못했습니다.'; }
