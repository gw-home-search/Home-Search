import { useEffect, useState, type FormEvent } from 'react';
import { adminRequest, type AdminPrincipal } from '../shared/http/adminHttp';
import { AccountsPage } from '../features/accounts/AccountsPage';
import { AuditPage } from '../features/audit/AuditPage';
import { CoordinatesPage } from '../features/coordinates/CoordinatesPage';
import { CoordinateReasonsPage } from '../features/coordinates/CoordinateReasonsPage';
import { MetadataPage } from '../features/metadata/MetadataPage';
export function AdminApp() {
  const [principal, setPrincipal] = useState<AdminPrincipal | null>(null); const [loading, setLoading] = useState(true);
  useEffect(() => { adminRequest<AdminPrincipal>('/api/v1/admin/auth/me').then(setPrincipal).catch(() => setPrincipal(null)).finally(() => setLoading(false)); }, []);
  useEffect(() => {
    const expire = () => setPrincipal(null);
    window.addEventListener('admin-session-expired', expire);
    return () => window.removeEventListener('admin-session-expired', expire);
  }, []);
  if (loading) return <main className="center" role="status">세션 확인 중</main>;
  if (!principal) return <Login onLogin={setPrincipal} />;
  return <Dashboard principal={principal} onLogout={() => setPrincipal(null)} />;
}
function Login({ onLogin }: { onLogin: (value: AdminPrincipal) => void }) {
  const [error, setError] = useState('');
  async function submit(event: FormEvent<HTMLFormElement>) { event.preventDefault(); const data = new FormData(event.currentTarget); try { onLogin(await adminRequest('/api/v1/admin/auth/login', { method: 'POST', body: JSON.stringify({ loginId: data.get('loginId'), password: data.get('password') }) })); } catch { setError('로그인 정보를 확인하세요.'); } }
  return <main className="login"><form onSubmit={submit}><p className="eyebrow">HOME SEARCH CONTROL PLANE</p><h1>관리자 로그인</h1><label>아이디<input name="loginId" autoComplete="username" required /></label><label>비밀번호<input name="password" type="password" autoComplete="current-password" required /></label><button>로그인</button>{error && <p role="alert">{error}</p>}</form></main>;
}
function Dashboard({ principal, onLogout }: { principal: AdminPrincipal; onLogout: () => void }) {
  const route = window.location.pathname; const allowed = (permission: string) => principal.permissions.includes(permission);
  const required = route.startsWith('/admin/accounts') ? 'ADMIN_ACCOUNT_MANAGE' : route.startsWith('/admin/audit') ? 'ADMIN_AUDIT_READ' : route.startsWith('/admin/metadata') ? 'METADATA_READ' : 'COORDINATE_READ';
  async function logout() { await adminRequest('/api/v1/admin/auth/logout', { method: 'POST' }); onLogout(); }
  if (!allowed(required)) return <main className="center"><h1>권한 부족</h1><p>이 화면을 볼 권한이 없습니다.</p></main>;
  return <main className="shell"><aside><h1>Home Search</h1><nav><a href="/admin/coordinates">좌표</a><a href="/admin/metadata">메타데이터</a>{allowed('ADMIN_ACCOUNT_MANAGE') && <a href="/admin/accounts">계정</a>}{allowed('ADMIN_AUDIT_READ') && <a href="/admin/audit">감사</a>}</nav></aside><section><header><div><p className="eyebrow">ADMIN SERVICE</p><h2>{route.includes('metadata') ? '메타데이터 관리' : route.includes('accounts') ? '계정 관리' : route.includes('audit') ? '보안 감사' : '좌표 관리'}</h2></div><div>{principal.displayName} <button onClick={() => void logout()}>로그아웃</button></div></header>
    {route.startsWith('/admin/accounts') ? <AccountsPage /> : route.startsWith('/admin/audit') ? <AuditPage /> : route.startsWith('/admin/metadata') ? <MetadataPage canRetry={allowed('METADATA_RETRY')} canHold={allowed('METADATA_HOLD')} canManageAlias={allowed('METADATA_ALIAS_MANAGE')} /> : route.startsWith('/admin/coordinates/reasons') ? <CoordinateReasonsPage /> : <CoordinatesPage canWrite={allowed('COORDINATE_WRITE')} />}
  </section></main>;
}
