import { useEffect, useState, type FormEvent } from 'react';
import { AdminHttpError } from '../../shared/http/adminHttp';
import { changeAccountStatus, createAccount, fetchAccounts, replaceAccountRoles, revokeAccountSessions, type AdminAccount } from './accountApi';

export function AccountsPage() {
  const [accounts, setAccounts] = useState<AdminAccount[]>([]);
  const [state, setState] = useState<'loading'|'ready'|'error'>('loading');
  const [message, setMessage] = useState('');

  async function reload() {
    setState('loading');
    try { setAccounts(await fetchAccounts()); setState('ready'); setMessage(''); }
    catch (error) { setState('error'); setMessage(errorMessage(error)); }
  }
  useEffect(() => { void reload(); }, []);

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const form = event.currentTarget; const data = new FormData(form);
    try {
      await createAccount({ loginId: text(data, 'loginId'), displayName: text(data, 'displayName'), password: rawText(data, 'password'), roles: [text(data, 'role')] });
      form.reset(); await reload(); setMessage('계정을 생성했습니다.');
    } catch (error) { setMessage(errorMessage(error)); }
  }
  async function mutate(action: () => Promise<void>, success: string) {
    try { await action(); await reload(); setMessage(success); }
    catch (error) { setMessage(errorMessage(error)); }
  }

  return <div className="admin-grid"><article className="panel"><div className="panel-title"><div><p className="eyebrow">ACCOUNTS</p><h3>관리자 계정</h3></div><button onClick={() => void reload()}>새로고침</button></div>
    {state === 'loading' ? <p role="status">계정 불러오는 중</p> : null}
    {state === 'error' ? <p role="alert">{message}</p> : null}
    {state === 'ready' && accounts.length === 0 ? <p>등록된 계정이 없습니다.</p> : null}
    <div className="table-wrap"><table><thead><tr><th>계정</th><th>Role</th><th>상태</th><th>작업</th></tr></thead><tbody>{accounts.map(account => <tr key={account.accountId}>
      <td><strong>{account.loginId}</strong><span>{account.displayName}</span></td><td>{account.roles.join(', ')}</td><td>{account.enabled ? '활성' : '비활성'}{account.lockedUntil ? ' · 잠김' : ''}</td>
      <td className="actions"><button aria-label={`${account.loginId} 세션 해제`} onClick={() => void mutate(() => revokeAccountSessions(account.accountId), '세션을 해제했습니다.')}>세션 해제</button>
        <button onClick={() => void mutate(() => changeAccountStatus(account.accountId, !account.enabled), '계정 상태를 변경했습니다.')}>{account.enabled ? '비활성화' : '활성화'}</button>
        <select multiple size={3} aria-label={`${account.loginId} role 변경`} value={account.roles} onChange={event => { const roles = Array.from(event.target.selectedOptions, option => option.value); if (roles.length > 0) void mutate(() => replaceAccountRoles(account.accountId, roles), 'Role을 변경했습니다.'); }}><option>VIEWER</option><option>OPERATOR</option><option>ADMIN</option></select></td>
    </tr>)}</tbody></table></div>{message && state !== 'error' ? <p role="status">{message}</p> : null}</article>
    <aside className="panel"><p className="eyebrow">CREATE</p><h3>새 계정</h3><form className="stack" onSubmit={create}>
      <label>로그인 ID<input name="loginId" required maxLength={100}/></label><label>표시 이름<input name="displayName" required maxLength={100}/></label>
      <label>초기 비밀번호<input name="password" type="password" required minLength={12} maxLength={200} autoComplete="new-password"/></label>
      <label>Role<select name="role"><option>VIEWER</option><option>OPERATOR</option><option>ADMIN</option></select></label><button>계정 생성</button>
    </form></aside></div>;
}
function text(data: FormData, key: string) { return String(data.get(key) ?? '').trim(); }
function rawText(data: FormData, key: string) { return String(data.get(key) ?? ''); }
function errorMessage(error: unknown) { return error instanceof AdminHttpError ? `${error.message}${error.requestId ? ` (${error.requestId})` : ''}` : '관리자 계정 요청에 실패했습니다.'; }
