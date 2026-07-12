import { useEffect, useState } from 'react';
import { AdminHttpError } from '../../shared/http/adminHttp';
import { fetchAuditEvents, type AuditEvent } from './auditApi';

export function AuditPage() {
  const [events, setEvents] = useState<AuditEvent[]>([]); const [error, setError] = useState(''); const [loading, setLoading] = useState(true);
  async function reload() { setLoading(true); try { setEvents(await fetchAuditEvents()); setError(''); } catch (value) { setError(value instanceof AdminHttpError ? value.message : '감사 기록 요청에 실패했습니다.'); } finally { setLoading(false); } }
  useEffect(() => { void reload(); }, []);
  return <article className="panel"><div className="panel-title"><div><p className="eyebrow">SECURITY AUDIT</p><h3>보안 이벤트</h3></div><button onClick={() => void reload()}>새로고침</button></div>
    {loading ? <p role="status">감사 기록 불러오는 중</p> : null}{error ? <p role="alert">{error}</p> : null}{!loading && !error && events.length === 0 ? <p>감사 기록이 없습니다.</p> : null}
    <div className="table-wrap"><table><thead><tr><th>시각</th><th>이벤트</th><th>결과</th><th>Request ID</th></tr></thead><tbody>{events.map(event => <tr key={event.id}><td>{new Date(event.createdAt).toLocaleString('ko-KR')}</td><td><strong>{event.eventType}</strong><span>{event.targetAccountId ?? '-'}</span></td><td>{event.success ? '성공' : '실패'}</td><td>{event.requestId ?? '-'}</td></tr>)}</tbody></table></div></article>;
}
