import { adminRequest } from '../../shared/http/adminHttp';

export type AdminAccount = {
  accountId: string;
  loginId: string;
  displayName: string;
  enabled: boolean;
  lockedUntil: string | null;
  roles: string[];
};

export function fetchAccounts() { return adminRequest<AdminAccount[]>('/api/v1/admin/accounts'); }
export function createAccount(request: {loginId: string; displayName: string; password: string; roles: string[]}) {
  return adminRequest<AdminAccount>('/api/v1/admin/accounts', { method: 'POST', body: JSON.stringify(request) });
}
export function replaceAccountRoles(accountId: string, roles: string[]) {
  return adminRequest<void>(`/api/v1/admin/accounts/${encodeURIComponent(accountId)}/roles`, { method: 'PUT', body: JSON.stringify({ roles }) });
}
export function changeAccountStatus(accountId: string, enabled: boolean) {
  return adminRequest<void>(`/api/v1/admin/accounts/${encodeURIComponent(accountId)}/status`, { method: 'PATCH', body: JSON.stringify({ enabled }) });
}
export function revokeAccountSessions(accountId: string) {
  return adminRequest<void>(`/api/v1/admin/accounts/${encodeURIComponent(accountId)}/sessions`, { method: 'DELETE' });
}
