import { useEffect, useState, type FormEvent } from 'react';

import {
  approveCoordinateOverride,
  fetchCoordinatePendingComplexes,
  fetchCoordinatePendingSummary,
  type CoordinatePendingComplex,
  type CoordinatePendingReasonCode,
  type CoordinatePendingSummary,
} from './api/fetchCoordinatePendingComplexes';

type AdminRequestState = 'loading' | 'ready' | 'empty' | 'error';

type CoordinateReasonGuide = {
  code: CoordinatePendingReasonCode;
  label: string;
  actionLabel: string;
  description: string;
  operatorNote: string;
  approveable: boolean;
};

const COORDINATE_REASON_GUIDES: CoordinateReasonGuide[] = [
  {
    code: 'PNU_COORDINATE_MISSING',
    label: 'PNU 좌표 없음',
    actionLabel: '수동 승인 가능',
    description: 'parcel/PNU 자체에 마커로 사용할 위도와 경도가 없습니다.',
    operatorNote: '주소나 외부 좌표원으로 PNU 위치를 확인한 뒤 수동 좌표를 승인합니다.',
    approveable: true,
  },
  {
    code: 'SAME_PNU_MULTI_COMPLEX',
    label: '동일 PNU 다중 단지',
    actionLabel: '단지별 표시 좌표 처리 필요',
    description: '하나의 PNU에 여러 단지가 있고 신뢰할 건물 footprint 표시 좌표가 없습니다.',
    operatorNote: 'parcel 좌표를 덮어쓰면 단지 구분이 사라지므로 단지별 display coordinate 처리가 필요합니다.',
    approveable: false,
  },
  {
    code: 'COMPLEX_DISPLAY_COORDINATE_MISSING',
    label: '단지 표시 좌표 없음',
    actionLabel: '단지별 표시 좌표 처리 필요',
    description: '같은 PNU의 일부 단지는 표시 좌표가 있지만 이 단지는 아직 없습니다.',
    operatorNote: '기존 parcel 좌표가 아니라 해당 complex의 표시 좌표를 별도로 보강해야 합니다.',
    approveable: false,
  },
];
const ADMIN_PAGE_SIZE = 50;
const ADMIN_ACCESS_STORAGE_KEY = 'home-search-admin-coordinate-access';
const ADMIN_ACCESS_CODE_STORAGE_KEY = 'home-search-admin-coordinate-access-code';

export function CoordinateOverrideAdminPage() {
  const [pending, setPending] = useState<CoordinatePendingComplex[]>([]);
  const [summary, setSummary] = useState<CoordinatePendingSummary | null>(null);
  const [state, setState] = useState<AdminRequestState>('loading');
  const [error, setError] = useState<string | null>(null);
  const [selectedPnu, setSelectedPnu] = useState('');
  const [submitMessage, setSubmitMessage] = useState<string | null>(null);
  const [reloadSeq, setReloadSeq] = useState(0);
  const [pageIndex, setPageIndex] = useState(0);
  const [hasNextPage, setHasNextPage] = useState(false);
  const [isAdminAuthorized, setIsAdminAuthorized] = useState(() => (
    hasStoredAdminAccess()
  ));

  useEffect(() => {
    if (!isAdminAuthorized) {
      setPending([]);
      setSummary(null);
      setState('ready');
      setError(null);
      return undefined;
    }

    let ignore = false;
    setState('loading');
    setError(null);
    setSummary(null);

    const adminAccessCode = getStoredAdminAccessCode();
    Promise.all([
      fetchCoordinatePendingComplexes({
        limit: ADMIN_PAGE_SIZE + 1,
        offset: pageIndex * ADMIN_PAGE_SIZE,
        adminAccessCode,
      }),
      fetchCoordinatePendingSummary(adminAccessCode),
    ])
      .then(([items, nextSummary]) => {
        if (ignore) {
          return;
        }
        setHasNextPage(items.length > ADMIN_PAGE_SIZE);
        setPending(items.slice(0, ADMIN_PAGE_SIZE));
        setSummary(nextSummary);
        setState(items.length === 0 ? 'empty' : 'ready');
      })
      .catch((nextError: unknown) => {
        if (ignore) {
          return;
        }
        setPending([]);
        setState('error');
        setError(nextError instanceof Error ? nextError.message : 'Unknown admin coordinate error');
      });

    return () => {
      ignore = true;
    };
  }, [isAdminAuthorized, pageIndex, reloadSeq]);

  async function handleAdminAccess(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formData = new FormData(event.currentTarget);
    const accessCode = stringFormValue(formData, 'accessCode').trim();
    if (accessCode.length === 0) {
      setError('관리자 접근 코드를 입력하세요');
      return;
    }

    try {
      await validateAdminAccessCode(accessCode);
    } catch {
      setError('관리자 접근 코드가 올바르지 않습니다');
      return;
    }

    window.sessionStorage.setItem(ADMIN_ACCESS_STORAGE_KEY, 'granted');
    window.sessionStorage.setItem(ADMIN_ACCESS_CODE_STORAGE_KEY, accessCode);
    setIsAdminAuthorized(true);
    setError(null);
  }

  function handleAdminSignOut() {
    window.sessionStorage.removeItem(ADMIN_ACCESS_STORAGE_KEY);
    window.sessionStorage.removeItem(ADMIN_ACCESS_CODE_STORAGE_KEY);
    setIsAdminAuthorized(false);
    setPageIndex(0);
    setSelectedPnu('');
    setSubmitMessage(null);
  }

  async function handleApprove(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formData = new FormData(event.currentTarget);
    const pnu = stringFormValue(formData, 'pnu').trim();
    const latitude = numberFormValue(formData, 'latitude');
    const longitude = numberFormValue(formData, 'longitude');
    const reason = stringFormValue(formData, 'reason').trim();
    const approvedBy = stringFormValue(formData, 'approvedBy').trim();

    setSubmitMessage(null);
    setError(null);
    try {
      const result = await approveCoordinateOverride(pnu, {
        latitude,
        longitude,
        reason,
        approvedBy,
      }, getStoredAdminAccessCode());
      setSubmitMessage(result.parcelUpdated ? '좌표 승인이 완료되었습니다' : 'override 저장 완료, parcel 갱신 없음');
      setSelectedPnu('');
      setReloadSeq((current) => current + 1);
    } catch (nextError) {
      setError(nextError instanceof Error ? nextError.message : 'Unknown admin coordinate error');
    }
  }

  if (!isAdminAuthorized) {
    return (
      <main className="admin-shell">
        <header className="admin-header">
          <div>
            <h1>관리자 접근</h1>
            <p>좌표 보강 관리는 관리자 전용 화면입니다.</p>
          </div>
          <nav className="admin-header-actions" aria-label="Admin navigation">
            <a href="/" aria-label="Back to map">지도로 돌아가기</a>
          </nav>
        </header>

        <section className="admin-access-panel" aria-label="Admin access gate">
          <form className="admin-override-form" aria-label="Admin access" onSubmit={handleAdminAccess}>
            <div className="panel-section-header">
              <p>접근 코드 확인</p>
              <span>관리자 전용</span>
            </div>
            <p className="admin-form-note">
              운영 좌표를 변경할 수 있는 화면입니다. 승인된 관리자만 접근 코드를 입력해
              보강 대기 목록을 조회할 수 있습니다.
            </p>
            <label>
              <span>접근 코드</span>
              <input name="accessCode" required type="password" autoComplete="current-password" />
            </label>
            <button type="submit">관리자 화면 열기</button>
            {error ? <p role="alert">{error}</p> : null}
          </form>
        </section>
      </main>
    );
  }

  return (
    <main className="admin-shell">
      <header className="admin-header">
        <div>
          <h1>좌표 보강 관리</h1>
          <p>거래 데이터는 저장됐지만 지도 마커로 표시하기 어려운 대상을 확인합니다.</p>
        </div>
        <nav className="admin-header-actions" aria-label="Admin navigation">
          <a href="/admin/coordinates/reasons">보강 사유 정리</a>
          <a href="/" aria-label="Back to map">지도로 돌아가기</a>
          <button type="button" onClick={handleAdminSignOut}>관리자 잠금</button>
        </nav>
      </header>

      <section className="admin-workspace" aria-label="Coordinate override workspace">
        <div className="admin-list-panel">
          <section className="admin-overview" aria-label="Coordinate pending overview">
            <div>
              <p className="admin-kicker">운영 기준</p>
              <h2>마커 표시를 막는 보강 사유를 먼저 확인합니다</h2>
              <p>
                PNU 좌표 없음만 이 화면에서 수동 승인합니다. 동일 PNU 다중 단지 계열은
                parcel 좌표를 덮어쓰지 않고 단지별 표시 좌표 처리로 분리합니다.
              </p>
            </div>
            <dl className="admin-summary">
              <div>
                <dt>전체 대기 항목</dt>
                <dd>{(summary?.totalCount ?? pending.length).toLocaleString()}</dd>
              </div>
              <div>
                <dt>현재 페이지 항목</dt>
                <dd>{pending.length.toLocaleString()}</dd>
              </div>
            </dl>
          </section>

          <div className="panel-section-header">
            <p>전체 사유 분포</p>
            <span>{hasNextPage ? '다음 페이지 있음' : '마지막 페이지'}</span>
          </div>
          <section className="reason-summary" aria-label="Coordinate pending reason summary">
            {COORDINATE_REASON_GUIDES.map((guide) => (
              <article className="reason-summary-item" key={guide.code}>
                <div>
                  <span className="reason-badge">{guide.label}</span>
                  <strong>{summaryReasonCount(summary, guide.code, pending).toLocaleString()}</strong>
                </div>
                <p>{guide.actionLabel}</p>
              </article>
            ))}
          </section>

          <div className="panel-section-header">
            <p>보강 대기 목록</p>
            <span>{pageIndex + 1}페이지</span>
          </div>

          {state === 'loading' ? <p role="status">좌표 보강 대상을 불러오는 중입니다</p> : null}
          {state === 'empty' ? <p role="status">현재 보강 대기 단지가 없습니다</p> : null}
          {state === 'error' && error ? <p role="alert">{error}</p> : null}

          {pending.length > 0 ? (
            <table className="admin-table">
              <thead>
                <tr>
                  <th scope="col">단지</th>
                  <th scope="col">PNU</th>
                  <th scope="col">사유</th>
                  <th scope="col">거래</th>
                  <th scope="col">작업</th>
                </tr>
              </thead>
              <tbody>
                {pending.map((item) => (
                  <tr key={`${item.parcelId}-${item.complexId}`}>
                    <td>
                      <strong>{item.aptName}</strong>
                      <span>{item.address ?? item.aptSeq ?? '주소 없음'}</span>
                    </td>
                    <td>{item.pnu}</td>
                    <td>
                      <span className="reason-cell-label">{reasonGuide(item.reason).label}</span>
                      <span>{item.reason}</span>
                    </td>
                    <td>{item.tradeCount.toLocaleString()}</td>
                    <td>
                      <button
                        type="button"
                        aria-label={`Select coordinate override ${item.pnu}`}
                        disabled={!canApproveParcelCoordinate(item)}
                        onClick={() => {
                          setSelectedPnu(item.pnu);
                        }}
                      >
                        {canApproveParcelCoordinate(item) ? '선택' : '표시 좌표 처리'}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : null}

          <nav className="admin-pagination" aria-label="Coordinate pending pagination">
            <button
              type="button"
              disabled={pageIndex === 0}
              onClick={() => {
                setPageIndex((current) => Math.max(0, current - 1));
              }}
            >
              이전
            </button>
            <span>{pageIndex + 1}페이지</span>
            <button
              type="button"
              disabled={!hasNextPage}
              onClick={() => {
                setPageIndex((current) => current + 1);
              }}
            >
              다음
            </button>
          </nav>
        </div>

        <form className="admin-override-form" aria-label="Approve coordinate override" onSubmit={handleApprove}>
          <div className="panel-section-header">
            <p>수동 좌표 승인</p>
            <span>PNU 좌표 없음만 처리</span>
          </div>
          <p className="admin-form-note">
            동일 PNU 다중 단지는 이 폼으로 처리하지 않습니다. 좌표를 입력하기 전에 PNU와 실제
            단지 위치가 같은지 확인하세요.
          </p>
          <label>
            <span>PNU</span>
            <input name="pnu" required pattern="\\d{19}" value={selectedPnu} onChange={(event) => {
              setSelectedPnu(event.currentTarget.value);
            }} />
          </label>
          <label>
            <span>위도</span>
            <input name="latitude" required min="33" max="39" step="0.0000001" type="number" />
          </label>
          <label>
            <span>경도</span>
            <input name="longitude" required min="124" max="132" step="0.0000001" type="number" />
          </label>
          <label>
            <span>승인 메모</span>
            <textarea name="reason" rows={4} />
          </label>
          <label>
            <span>승인자</span>
            <input name="approvedBy" required defaultValue="local-operator" />
          </label>
          <button type="submit" aria-label="Approve coordinate override">
            승인
          </button>
          {submitMessage ? <p role="status">{submitMessage}</p> : null}
          {state !== 'error' && error ? <p role="alert">{error}</p> : null}
        </form>
      </section>
    </main>
  );
}

export function CoordinateReasonGuidePage() {
  const [isAdminAuthorized, setIsAdminAuthorized] = useState(() => (
    hasStoredAdminAccess()
  ));
  const [error, setError] = useState<string | null>(null);

  async function handleAdminAccess(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formData = new FormData(event.currentTarget);
    const accessCode = stringFormValue(formData, 'accessCode').trim();
    if (accessCode.length === 0) {
      setError('관리자 접근 코드를 입력하세요');
      return;
    }

    try {
      await validateAdminAccessCode(accessCode);
    } catch {
      setError('관리자 접근 코드가 올바르지 않습니다');
      return;
    }

    window.sessionStorage.setItem(ADMIN_ACCESS_STORAGE_KEY, 'granted');
    window.sessionStorage.setItem(ADMIN_ACCESS_CODE_STORAGE_KEY, accessCode);
    setIsAdminAuthorized(true);
    setError(null);
  }

  function handleAdminSignOut() {
    window.sessionStorage.removeItem(ADMIN_ACCESS_STORAGE_KEY);
    window.sessionStorage.removeItem(ADMIN_ACCESS_CODE_STORAGE_KEY);
    setIsAdminAuthorized(false);
  }

  if (!isAdminAuthorized) {
    return (
      <AdminAccessScreen
        error={error}
        onSubmit={handleAdminAccess}
      />
    );
  }

  return (
    <main className="admin-shell">
      <header className="admin-header">
        <div>
          <h1>보강 사유 정리</h1>
          <p>좌표 보강 대기 목록의 reason이 어떤 작업을 요구하는지 정리합니다.</p>
        </div>
        <nav className="admin-header-actions" aria-label="Admin navigation">
          <a href="/admin/coordinates">좌표 보강 관리</a>
          <a href="/" aria-label="Back to map">지도로 돌아가기</a>
          <button type="button" onClick={handleAdminSignOut}>관리자 잠금</button>
        </nav>
      </header>

      <section className="reason-guide-page" aria-label="Coordinate pending reason guide">
        <div className="reason-guide-intro">
          <p className="admin-kicker">사유 안내</p>
          <h2>지도 마커에 바로 쓸 수 없는 이유를 세 가지로만 나눕니다</h2>
          <p>
            이 reason은 RTMS 거래 저장을 막기 위한 값이 아닙니다. 저장된 거래가 아직
            마커 표시 가능 상태가 아닌 이유와 운영자가 선택해야 할 보강 경로를 설명합니다.
          </p>
        </div>

        <div className="reason-guide-grid">
          {COORDINATE_REASON_GUIDES.map((guide) => (
            <article className="reason-guide-card" key={guide.code}>
              <div className="reason-guide-card-header">
                <span className="reason-badge">{guide.label}</span>
                <span>{guide.actionLabel}</span>
              </div>
              <h3>{guide.code}</h3>
              <p>{guide.description}</p>
              <dl>
                <div>
                  <dt>처리 방식</dt>
                  <dd>{guide.operatorNote}</dd>
                </div>
                <div>
                  <dt>수동 좌표 승인</dt>
                  <dd>{guide.approveable ? '수동 승인 가능' : '단지별 표시 좌표 처리 필요'}</dd>
                </div>
              </dl>
            </article>
          ))}
        </div>
      </section>
    </main>
  );
}

function AdminAccessScreen({
  error,
  onSubmit,
}: {
  error: string | null;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
}) {
  return (
    <main className="admin-shell">
      <header className="admin-header">
        <div>
          <h1>관리자 접근</h1>
          <p>좌표 보강 관리는 관리자 전용 화면입니다.</p>
        </div>
        <nav className="admin-header-actions" aria-label="Admin navigation">
          <a href="/" aria-label="Back to map">지도로 돌아가기</a>
        </nav>
      </header>

      <section className="admin-access-panel" aria-label="Admin access gate">
        <form className="admin-override-form" aria-label="Admin access" onSubmit={onSubmit}>
          <div className="panel-section-header">
            <p>접근 코드 확인</p>
            <span>관리자 전용</span>
          </div>
          <p className="admin-form-note">
            운영 좌표를 변경할 수 있는 화면입니다. 승인된 관리자만 접근 코드를 입력해
            보강 대기 목록을 조회할 수 있습니다.
          </p>
          <label>
            <span>접근 코드</span>
            <input name="accessCode" required type="password" autoComplete="current-password" />
          </label>
          <button type="submit">관리자 화면 열기</button>
          {error ? <p role="alert">{error}</p> : null}
        </form>
      </section>
    </main>
  );
}

function stringFormValue(formData: FormData, field: string): string {
  const value = formData.get(field);
  return typeof value === 'string' ? value : '';
}

function numberFormValue(formData: FormData, field: string): number {
  const parsed = Number(stringFormValue(formData, field));
  return Number.isFinite(parsed) ? parsed : Number.NaN;
}

function canApproveParcelCoordinate(item: CoordinatePendingComplex): boolean {
  return item.reason === 'PNU_COORDINATE_MISSING';
}

function reasonGuide(reason: string): CoordinateReasonGuide {
  return COORDINATE_REASON_GUIDES.find((guide) => guide.code === reason) ?? {
    code: 'PNU_COORDINATE_MISSING',
    label: reason,
    actionLabel: '확인 필요',
    description: '문서화되지 않은 보강 사유입니다.',
    operatorNote: 'API contract와 backend reason taxonomy를 먼저 확인하세요.',
    approveable: false,
  };
}

function reasonCount(pending: CoordinatePendingComplex[], reason: CoordinatePendingReasonCode): number {
  return pending.filter((item) => item.reason === reason).length;
}

function summaryReasonCount(
  summary: CoordinatePendingSummary | null,
  reason: CoordinatePendingReasonCode,
  pending: CoordinatePendingComplex[],
): number {
  return summary?.reasonCounts[reason] ?? reasonCount(pending, reason);
}

function hasStoredAdminAccess(): boolean {
  return (
    window.sessionStorage.getItem(ADMIN_ACCESS_STORAGE_KEY) === 'granted'
    && getStoredAdminAccessCode().length > 0
  );
}

function getStoredAdminAccessCode(): string {
  return window.sessionStorage.getItem(ADMIN_ACCESS_CODE_STORAGE_KEY) ?? '';
}

async function validateAdminAccessCode(accessCode: string): Promise<void> {
  await fetchCoordinatePendingComplexes({
    limit: 1,
    offset: 0,
    adminAccessCode: accessCode,
  });
}
