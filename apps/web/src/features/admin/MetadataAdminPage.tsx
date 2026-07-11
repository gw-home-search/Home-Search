import { useEffect, useState, type FormEvent } from 'react';

import './admin.css';
import {
  approveBuildingMetadataAlias, changeMetadataAlias, decideBuildingMetadata,
  decideBuildingMetadataChange, fetchBuildingMetadataDetail, fetchBuildingMetadataPending,
  fetchMetadataAliases, fetchMetadataPending, fetchMetadataSummary, holdMetadata,
  linkBuildingMetadataIdentity, proposeMetadataAlias, retryMetadata,
  type BuildingMetadataDetail, type BuildingMetadataPending, type MetadataAlias,
  type MetadataPending, type MetadataSummary,
} from './api/metadataAdminApi';

const ACCESS_KEY = 'home-search-admin-metadata-access-code';

export function MetadataAdminPage() {
  const [code, setCode] = useState(() => window.sessionStorage.getItem(ACCESS_KEY) ?? '');
  const [tab, setTab] = useState<'building' | 'legacy'>('building');
  const [pending, setPending] = useState<MetadataPending[]>([]);
  const [buildingPending, setBuildingPending] = useState<BuildingMetadataPending[]>([]);
  const [buildingDetail, setBuildingDetail] = useState<BuildingMetadataDetail | null>(null);
  const [aliases, setAliases] = useState<MetadataAlias[]>([]);
  const [summary, setSummary] = useState<MetadataSummary | null>(null);
  const [message, setMessage] = useState('');

  const reload = async (accessCode = code) => {
    try {
      const [items, nextSummary, nextAliases, nextBuilding] = await Promise.all([
        fetchMetadataPending(accessCode), fetchMetadataSummary(accessCode), fetchMetadataAliases(accessCode),
        fetchBuildingMetadataPending(accessCode),
      ]);
      setPending(items); setSummary(nextSummary); setAliases(nextAliases); setBuildingPending(nextBuilding); setMessage('');
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
  async function loadBuildingDetail(complexId: number) {
    try { setBuildingDetail(await fetchBuildingMetadataDetail(complexId, code)); setMessage(''); }
    catch (error) { setMessage(error instanceof Error ? error.message : '건축 메타데이터 상세 조회 실패'); }
  }
  async function decideLegacy(complexId: number, action: 'retry' | 'hold') {
    const decision = { actor: 'local-operator', reason: action === 'retry' ? '관리자 재시도 요청' : '관리자 검토 보류' };
    if (action === 'retry') await retryMetadata(complexId, decision, code); else await holdMetadata(complexId, decision, code);
    await reload();
  }
  async function decideBuilding(item: BuildingMetadataPending, action: 'retry' | 'hold') {
    await decideBuildingMetadata(item.complexId, action, {
      actor: 'local-operator', reason: action === 'retry' ? '건축 metadata 재시도' : '건축 metadata HOLD',
      expectedStateVersion: item.stateVersion,
    }, code);
    setBuildingDetail(null); await reload();
  }
  async function decideChange(action: 'approve' | 'reject') {
    if (!buildingDetail?.complex.pendingEvaluationId) return;
    await decideBuildingMetadataChange(buildingDetail.complex.complexId, buildingDetail.complex.pendingEvaluationId, action, {
      actor: 'local-operator', reason: action === 'approve' ? '원천값 비교 승인' : '원천값 변경 거절',
      expectedStateVersion: buildingDetail.complex.stateVersion,
    }, code);
    setBuildingDetail(null); await reload();
  }
  async function linkIdentity(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!buildingDetail) return;
    const data = new FormData(event.currentTarget);
    await linkBuildingMetadataIdentity(buildingDetail.complex.complexId,
      String(data.get('source')) as 'ODC_COMPLEX_PK' | 'BLD_MGM_BLD_RGST_PK', String(data.get('sourceKey') ?? ''), {
        actor: 'local-operator', reason: String(data.get('reason') ?? ''),
        expectedStateVersion: buildingDetail.complex.stateVersion,
      }, code);
    setBuildingDetail(null); await reload();
  }
  async function approveAlias(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!buildingDetail) return;
    const data = new FormData(event.currentTarget);
    await approveBuildingMetadataAlias(buildingDetail.complex.complexId,
      String(data.get('aliasType')) as 'ADMIN_ALIAS' | 'BUILDING_REGISTER_NAME', String(data.get('aliasName') ?? ''), {
        actor: 'local-operator', reason: String(data.get('reason') ?? ''),
        expectedStateVersion: buildingDetail.complex.stateVersion,
      }, code);
    setBuildingDetail(null); await reload();
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
      <p>원문 snapshot, 식별 근거, replay와 변경 승인을 관리합니다.</p></div>
      <nav className="admin-header-actions"><a href="/admin/coordinates">좌표 관리</a><a href="/">지도로 돌아가기</a></nav>
    </header>
    <section className="admin-workspace"><div className="admin-list-panel">
      <section className="admin-overview"><div><p className="admin-kicker">운영 원칙</p>
        <h2>ODC identity와 건축물대장 관리번호는 기존 apt_seq와 분리합니다</h2></div>
        <dl className="admin-summary"><div><dt>건축 검토 대상</dt><dd>{buildingPending.length}</dd></div>
          <div><dt>기존 검토 대상</dt><dd>{summary?.totalCount ?? pending.length}</dd></div></dl>
      </section>
      <div className="admin-header-actions" role="tablist" aria-label="메타데이터 종류">
        <button role="tab" aria-selected={tab === 'building'} onClick={() => setTab('building')}>건축 metadata</button>
        <button role="tab" aria-selected={tab === 'legacy'} onClick={() => setTab('legacy')}>ODC PNU alias</button>
      </div>
      {message ? <p role="alert">{message}</p> : null}
      {tab === 'building' ? <BuildingPendingTable items={buildingPending} onSelect={loadBuildingDetail} onDecide={decideBuilding} />
        : <LegacyPendingTable items={pending} onDecide={decideLegacy} />}
    </div>
    {tab === 'building' ? <BuildingReviewPanel detail={buildingDetail} onIdentity={linkIdentity} onAlias={approveAlias}
      onChange={decideChange} /> : <LegacyAliasPanel aliases={aliases} onPropose={propose} onAction={aliasAction} />}
    </section>
  </main>;
}

function BuildingPendingTable({ items, onSelect, onDecide }: {
  items: BuildingMetadataPending[]; onSelect: (id: number) => Promise<void>;
  onDecide: (item: BuildingMetadataPending, action: 'retry' | 'hold') => Promise<void>;
}) {
  return <table className="admin-table"><thead><tr><th>단지</th><th>PNU / 상태</th><th>근거</th><th>작업</th></tr></thead>
    <tbody>{items.map(item => <tr key={item.complexId}><td><button onClick={() => void onSelect(item.complexId)}>
      <strong>{item.canonicalName}</strong></button><span>{item.aptSeq ?? '-'}</span></td>
      <td><strong>{item.pnu}</strong><span>{item.status} / v{item.stateVersion}</span></td>
      <td><span>{item.failureReason ?? '검토 사유 없음'}</span><span>evaluation {item.pendingEvaluationId ?? '-'}</span></td>
      <td><button onClick={() => void onDecide(item, 'retry')}>재시도</button>
        <button onClick={() => void onDecide(item, 'hold')}>HOLD</button></td></tr>)}</tbody></table>;
}

function LegacyPendingTable({ items, onDecide }: { items: MetadataPending[]; onDecide: (id: number, action: 'retry' | 'hold') => Promise<void> }) {
  return <table className="admin-table"><thead><tr><th>단지</th><th>PNU / 상태</th><th>증거</th><th>작업</th></tr></thead>
    <tbody>{items.map(item => <tr key={item.complexId}><td><strong>{item.aptName}</strong><span>{item.aptSeq}</span></td>
      <td><strong>{item.canonicalPnu}</strong><span>{item.status} / {item.failureKind ?? '-'}</span></td>
      <td><span>{item.failureReason ?? '실패 사유 없음'}</span><span>{item.holdReason ?? `시도 ${item.attempts}`}</span></td>
      <td><button onClick={() => void onDecide(item.complexId, 'retry')}>재시도</button>
        <button onClick={() => void onDecide(item.complexId, 'hold')}>HOLD</button></td></tr>)}</tbody></table>;
}

function BuildingReviewPanel({ detail, onIdentity, onAlias, onChange }: {
  detail: BuildingMetadataDetail | null; onIdentity: (event: FormEvent<HTMLFormElement>) => Promise<void>;
  onAlias: (event: FormEvent<HTMLFormElement>) => Promise<void>; onChange: (action: 'approve' | 'reject') => Promise<void>;
}) {
  if (!detail) return <aside className="admin-override-form"><h2>건축 metadata 검토</h2><p>단지를 선택하면 내부·외부 후보와 원문을 표시합니다.</p></aside>;
  const evaluation = detail.evaluations[0];
  return <aside className="admin-override-form"><h2>{detail.complex.canonicalName}</h2>
    <p>{detail.complex.status} · match {evaluation?.matchPath ?? '-'}</p>
    <h3>필드 비교</h3>{detail.comparisons.map(item => <p key={item.field}>
      <strong>{item.field}</strong> {item.currentValue ?? '-'} → {item.candidateValue ?? '-'} {item.conflict ? '충돌' : ''}</p>)}
    {detail.complex.pendingEvaluationId ? <div><button onClick={() => void onChange('approve')}>변경 승인</button>
      <button onClick={() => void onChange('reject')}>변경 거절</button></div> : null}
    <h3>PNU 내부 추천</h3>{detail.recommendations.slice(0, 5).map(item => <p key={`${item.complexId}-${item.name}`}>
      {item.name} · {item.score.toFixed(3)}</p>)}
    <form onSubmit={onIdentity}><h3>source identity 연결</h3><select name="source">
      <option value="ODC_COMPLEX_PK">ODC_COMPLEX_PK</option><option value="BLD_MGM_BLD_RGST_PK">BLD_MGM_BLD_RGST_PK</option>
    </select><input name="sourceKey" placeholder="source key" required /><textarea name="reason" placeholder="연결 근거" required />
      <button>identity 연결</button></form>
    <form onSubmit={onAlias}><h3>이름 alias 승인</h3><select name="aliasType"><option value="ADMIN_ALIAS">ADMIN_ALIAS</option>
      <option value="BUILDING_REGISTER_NAME">BUILDING_REGISTER_NAME</option></select>
      <input name="aliasName" placeholder="승인 이름" required /><textarea name="reason" placeholder="승인 근거" required /><button>alias 승인</button></form>
    <details><summary>원문 JSON</summary><pre>{evaluation?.rawBody ?? `원문 미저장 (${evaluation?.bodyByteSize ?? 0} bytes)`}</pre></details>
  </aside>;
}

function LegacyAliasPanel({ aliases, onPropose, onAction }: {
  aliases: MetadataAlias[]; onPropose: (event: FormEvent<HTMLFormElement>) => Promise<void>;
  onAction: (id: number, action: 'approve' | 'disable') => Promise<void>;
}) {
  return <aside className="admin-override-form"><h2>ODC PNU alias</h2>
    {aliases.map(alias => <div key={alias.id}><strong>{alias.canonicalPrefix} → {alias.sourcePrefix}</strong>
      <p>{alias.status}</p>{alias.status !== 'APPROVED' ? <button onClick={() => void onAction(alias.id, 'approve')}>승인</button> : null}
      {alias.status !== 'DISABLED' ? <button onClick={() => void onAction(alias.id, 'disable')}>비활성화</button> : null}</div>)}
    <form onSubmit={onPropose}><label><span>현재 prefix</span><input name="canonicalPrefix" pattern="\d{8}" required /></label>
      <label><span>ODC 구 prefix</span><input name="sourcePrefix" pattern="\d{8}" required /></label>
      <label><span>근거</span><textarea name="reason" required /></label><button>alias 제안</button></form>
  </aside>;
}
