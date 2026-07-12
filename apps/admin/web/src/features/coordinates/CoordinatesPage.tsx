import { useEffect, useState, type FormEvent } from 'react';
import { AdminHttpError } from '../../shared/http/adminHttp';
import {
  fetchCoordinatePending,
  fetchCoordinateSummary,
  overrideCoordinate,
  type CoordinatePendingComplex,
  type CoordinatePendingReason,
  type CoordinatePendingSummary,
} from './coordinateApi';

const reasonLabels: Record<CoordinatePendingReason, string> = {
  PNU_COORDINATE_MISSING: 'PNU 좌표 없음',
  SAME_PNU_MULTI_COMPLEX: '동일 PNU 다중 단지',
  COMPLEX_DISPLAY_COORDINATE_MISSING: '단지 표시 좌표 없음',
};

export function CoordinatesPage({ canWrite }: { canWrite: boolean }) {
  const [items, setItems] = useState<CoordinatePendingComplex[]>([]);
  const [summary, setSummary] = useState<CoordinatePendingSummary | null>(null);
  const [selectedPnu, setSelectedPnu] = useState('');
  const [loading, setLoading] = useState(true);
  const [message, setMessage] = useState('');

  async function reload() {
    setLoading(true);
    try {
      const [nextItems, nextSummary] = await Promise.all([fetchCoordinatePending(), fetchCoordinateSummary()]);
      setItems(nextItems);
      setSummary(nextSummary);
      setMessage('');
    } catch (error) {
      setMessage(errorMessage(error, '좌표 대기 목록 요청에 실패했습니다.'));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void reload(); }, []);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    try {
      const result = await overrideCoordinate(selectedPnu, {
        latitude: Number(data.get('latitude')),
        longitude: Number(data.get('longitude')),
        reason: String(data.get('reason') ?? '').trim(),
      });
      form.reset();
      setSelectedPnu('');
      await reload();
      setMessage(result.parcelUpdated ? '좌표 override를 적용했습니다.' : 'override를 저장했지만 parcel 좌표는 변경되지 않았습니다.');
    } catch (error) {
      setMessage(errorMessage(error, '좌표 override 요청에 실패했습니다.'));
    }
  }

  return <div className="admin-grid">
    <article className="panel">
      <div className="panel-title"><div><p className="eyebrow">COORDINATE QUEUE</p><h3>좌표 보강 대기</h3></div><button onClick={() => void reload()}>새로고침</button></div>
      <div className="summary-strip"><span>전체 <strong>{summary?.totalCount ?? items.length}</strong></span>{summary && Object.entries(summary.reasonCounts).map(([reason, count]) => <span key={reason}>{reasonLabels[reason as CoordinatePendingReason]} <strong>{count}</strong></span>)}</div>
      {loading ? <p role="status">좌표 대기 목록 불러오는 중</p> : null}
      {!loading && items.length === 0 ? <p>현재 좌표 보강 대기가 없습니다.</p> : null}
      <div className="table-wrap"><table><thead><tr><th>단지</th><th>PNU</th><th>사유</th><th>거래</th><th>작업</th></tr></thead><tbody>{items.map(item => {
        const approveable = item.reason === 'PNU_COORDINATE_MISSING';
        return <tr key={`${item.parcelId}-${item.complexId}`}><td><strong>{item.aptName}</strong><span>{item.address ?? item.aptSeq ?? '-'}</span></td><td>{item.pnu}</td><td>{reasonLabels[item.reason]}</td><td>{item.tradeCount.toLocaleString()}</td><td><button data-pnu={item.pnu} disabled={!canWrite || !approveable} onClick={() => setSelectedPnu(item.pnu)}>{approveable ? '선택' : '단지별 처리 필요'}</button></td></tr>;
      })}</tbody></table></div>
      {message ? <p role={message.includes('실패') ? 'alert' : 'status'}>{message}</p> : null}
    </article>
    <aside className="panel"><p className="eyebrow">OVERRIDE</p><h3>수동 좌표 승인</h3>{canWrite ? <form className="stack" aria-label="좌표 override" onSubmit={submit}>
      <label>PNU<input name="pnu" value={selectedPnu} readOnly required /></label>
      <label>위도<input name="latitude" type="number" min="33" max="39" step="any" required /></label>
      <label>경도<input name="longitude" type="number" min="124" max="132" step="any" required /></label>
      <label>근거<textarea name="reason" minLength={3} maxLength={500} required /></label>
      <button disabled={!selectedPnu}>override 적용</button>
    </form> : <p>조회 권한만 있어 좌표를 변경할 수 없습니다.</p>}</aside>
  </div>;
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof AdminHttpError ? `${error.message}${error.requestId ? ` (${error.requestId})` : ''}` : fallback;
}
