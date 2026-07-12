import { useEffect, useState, type FormEvent } from 'react';
import { AdminHttpError } from '../../shared/http/adminHttp';
import {
  changeMetadataAlias,
  fetchMetadataAliases,
  fetchMetadataPending,
  fetchMetadataSummary,
  holdMetadata,
  proposeMetadataAlias,
  retryMetadata,
  type MetadataAlias,
  type MetadataPending,
  type MetadataSummary,
} from './metadataApi';

type Permissions = { canRetry: boolean; canHold: boolean; canManageAlias: boolean };

export function MetadataPage({ canRetry, canHold, canManageAlias }: Permissions) {
  const [items, setItems] = useState<MetadataPending[]>([]);
  const [aliases, setAliases] = useState<MetadataAlias[]>([]);
  const [summary, setSummary] = useState<MetadataSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [message, setMessage] = useState('');

  async function reload() {
    setLoading(true);
    try {
      const [nextItems, nextSummary, nextAliases] = await Promise.all([fetchMetadataPending(), fetchMetadataSummary(), fetchMetadataAliases()]);
      setItems(nextItems); setSummary(nextSummary); setAliases(nextAliases); setMessage('');
    } catch (error) { setMessage(errorMessage(error)); }
    finally { setLoading(false); }
  }
  useEffect(() => { void reload(); }, []);

  async function decide(item: MetadataPending, action: 'retry' | 'hold') {
    try {
      if (action === 'retry') await retryMetadata(item.complexId, '관리자 재시도 요청');
      else await holdMetadata(item.complexId, '관리자 HOLD 요청');
      await reload(); setMessage(action === 'retry' ? '재시도를 요청했습니다.' : 'HOLD를 요청했습니다.');
    } catch (error) { setMessage(errorMessage(error)); }
  }

  async function propose(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const form = event.currentTarget; const data = new FormData(form);
    try {
      await proposeMetadataAlias({ canonicalPrefix: text(data, 'canonicalPrefix'), sourcePrefix: text(data, 'sourcePrefix'), reason: text(data, 'reason') });
      form.reset(); await reload(); setMessage('PNU alias를 제안했습니다.');
    } catch (error) { setMessage(errorMessage(error)); }
  }

  async function aliasAction(alias: MetadataAlias, action: 'approve' | 'disable') {
    try { await changeMetadataAlias(alias.id, action, `관리자 alias ${action} 요청`); await reload(); setMessage('Alias 상태를 변경했습니다.'); }
    catch (error) { setMessage(errorMessage(error)); }
  }

  return <div className="admin-grid">
    <article className="panel"><div className="panel-title"><div><p className="eyebrow">METADATA QUEUE</p><h3>메타데이터 보강 대기</h3></div><button onClick={() => void reload()}>새로고침</button></div>
      <div className="summary-strip"><span>전체 <strong>{summary?.totalCount ?? items.length}</strong></span>{summary && Object.entries(summary.statusCounts).map(([status, count]) => <span key={status}>{status} <strong>{count}</strong></span>)}</div>
      {loading ? <p role="status">메타데이터 대기 목록 불러오는 중</p> : null}{!loading && items.length === 0 ? <p>현재 보강 대기가 없습니다.</p> : null}
      <div className="table-wrap"><table><thead><tr><th>단지</th><th>PNU / 상태</th><th>실패 근거</th><th>작업</th></tr></thead><tbody>{items.map(item => <tr key={item.complexId}><td><strong>{item.aptName}</strong><span>{item.aptSeq ?? item.address ?? '-'}</span></td><td><strong>{item.canonicalPnu}</strong><span>{item.status} · 시도 {item.attempts}</span></td><td>{item.failureReason ?? item.holdReason ?? '-'}</td><td className="actions"><button aria-label={`${item.aptName} 재시도`} disabled={!canRetry} onClick={() => void decide(item, 'retry')}>재시도</button><button aria-label={`${item.aptName} HOLD`} disabled={!canHold} onClick={() => void decide(item, 'hold')}>HOLD</button></td></tr>)}</tbody></table></div>
      {message ? <p role={message.includes('실패') ? 'alert' : 'status'}>{message}</p> : null}
    </article>
    <aside className="panel"><p className="eyebrow">PNU ALIAS</p><h3>Prefix alias</h3>{aliases.length === 0 ? <p>등록된 alias가 없습니다.</p> : aliases.map(alias => <div className="alias-item" key={alias.id}><strong>{alias.canonicalPrefix} → {alias.sourcePrefix}</strong><span>{alias.status}</span>{canManageAlias ? <div className="actions"><button disabled={alias.status === 'APPROVED'} onClick={() => void aliasAction(alias, 'approve')}>승인</button><button disabled={alias.status === 'DISABLED'} onClick={() => void aliasAction(alias, 'disable')}>비활성화</button></div> : null}</div>)}
      {canManageAlias ? <form className="stack" onSubmit={propose}><label>현재 prefix<input name="canonicalPrefix" pattern="\d{8}" required /></label><label>원천 prefix<input name="sourcePrefix" pattern="\d{8}" required /></label><label>근거<textarea name="reason" minLength={3} maxLength={500} required /></label><button>alias 제안</button></form> : <p>Alias 관리 권한이 없습니다.</p>}</aside>
  </div>;
}

function text(data: FormData, key: string) { return String(data.get(key) ?? '').trim(); }
function errorMessage(error: unknown) { return error instanceof AdminHttpError ? `${error.message}${error.requestId ? ` (${error.requestId})` : ''}` : '메타데이터 관리자 요청에 실패했습니다.'; }
