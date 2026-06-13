import { useEffect, useState, type FormEvent } from 'react';
import {
  changeMetadataAlias, fetchMetadataAliases, fetchMetadataPending, fetchMetadataSummary,
  holdMetadata, proposeMetadataAlias, retryMetadata, type MetadataAlias, type MetadataPending,
  type MetadataSummary,
} from './api/metadataAdminApi';

const ACCESS_KEY = 'home-search-admin-metadata-access-code';

export function MetadataAdminPage() {
  const [code, setCode] = useState(() => window.sessionStorage.getItem(ACCESS_KEY) ?? '');
  const [pending, setPending] = useState<MetadataPending[]>([]);
  const [aliases, setAliases] = useState<MetadataAlias[]>([]);
  const [summary, setSummary] = useState<MetadataSummary | null>(null);
  const [message, setMessage] = useState('');

  const reload = async (accessCode = code) => {
    try {
      const [items, nextSummary, nextAliases] = await Promise.all([
        fetchMetadataPending(accessCode), fetchMetadataSummary(accessCode), fetchMetadataAliases(accessCode),
      ]);
      setPending(items); setSummary(nextSummary); setAliases(nextAliases); setMessage('');
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '메타데이터 관리자 요청 실패');
    }
  };

  useEffect(() => { if (code) void reload(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  async function login(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const next = String(new FormData(event.currentTarget).get('accessCode') ?? '').trim();
    window.sessionStorage.setItem(ACCESS_KEY, next); setCode(next); await reload(next);
  }
  async function decide(complexId: number, action: 'retry' | 'hold') {
    const decision = { actor: 'local-operator', reason: action === 'retry' ? '관리자 재시도 요청' : '관리자 검토 보류' };
    if (action === 'retry') await retryMetadata(complexId, decision, code); else await holdMetadata(complexId, decision, code);
    await reload();
  }
  async function propose(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const data = new FormData(event.currentTarget);
    await proposeMetadataAlias({
      canonicalPrefix: String(data.get('canonicalPrefix') ?? ''), sourcePrefix: String(data.get('sourcePrefix') ?? ''),
      actor: 'local-operator', reason: String(data.get('reason') ?? ''),
    }, code); await reload();
  }
  async function aliasAction(id: number, action: 'approve' | 'disable') {
    await changeMetadataAlias(id, action, { actor: 'local-operator', reason: `관리자 alias ${action}` }, code); await reload();
  }

  if (!code) return <main className="admin-shell"><section className="admin-access-panel">
    <form className="admin-override-form" aria-label="메타데이터 관리자 접근" onSubmit={login}>
      <h1>메타데이터 관리자 접근</h1><input name="accessCode" type="password" required /><button>관리자 화면 열기</button>
    </form>
  </section></main>;

  return <main className="admin-shell">
    <header className="admin-header"><div><h1>단지 메타데이터 관리</h1>
      <p>ODC 조회 증거와 재시도·HOLD·PNU alias 승인을 관리합니다.</p></div>
      <nav className="admin-header-actions"><a href="/admin/coordinates">좌표 관리</a><a href="/">지도로 돌아가기</a></nav>
    </header>
    <section className="admin-workspace"><div className="admin-list-panel">
      <section className="admin-overview"><div><p className="admin-kicker">운영 원칙</p>
        <h2>운영 PNU는 변경하지 않고 ODC 전용 alias만 승인합니다</h2></div>
        <dl className="admin-summary"><div><dt>검토 대상</dt><dd>{summary?.totalCount ?? pending.length}</dd></div></dl>
      </section>
      {message ? <p role="alert">{message}</p> : null}
      <table className="admin-table"><thead><tr><th>단지</th><th>PNU / 상태</th><th>증거</th><th>작업</th></tr></thead>
        <tbody>{pending.map(item => <tr key={item.complexId}><td><strong>{item.aptName}</strong><span>{item.aptSeq}</span></td>
          <td><strong>{item.canonicalPnu}</strong><span>{item.status} / {item.failureKind ?? '-'}</span></td>
          <td><span>{item.failureReason ?? '실패 사유 없음'}</span><span>{item.holdReason ?? `시도 ${item.attempts}`}</span></td>
          <td><button onClick={() => void decide(item.complexId, 'retry')}>재시도</button>
            <button onClick={() => void decide(item.complexId, 'hold')}>HOLD</button></td></tr>)}</tbody>
      </table>
    </div><aside className="admin-override-form"><h2>ODC PNU alias</h2>
      {aliases.map(alias => <div key={alias.id}><strong>{alias.canonicalPrefix} → {alias.sourcePrefix}</strong>
        <p>{alias.status}</p>{alias.status !== 'APPROVED' ? <button onClick={() => void aliasAction(alias.id, 'approve')}>승인</button> : null}
        {alias.status !== 'DISABLED' ? <button onClick={() => void aliasAction(alias.id, 'disable')}>비활성화</button> : null}</div>)}
      <form onSubmit={propose}><label><span>현재 prefix</span><input name="canonicalPrefix" pattern="\\d{8}" required /></label>
        <label><span>ODC 구 prefix</span><input name="sourcePrefix" pattern="\\d{8}" required /></label>
        <label><span>근거</span><textarea name="reason" required /></label><button>alias 제안</button></form>
    </aside></section>
  </main>;
}
