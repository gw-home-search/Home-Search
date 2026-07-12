import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, expect, it, vi } from 'vitest';

import { AdminApp } from './AdminApp';

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.pushState({}, '', '/');
  document.body.innerHTML = '';
});

it('계정 관리 route에서 계정 목록과 session revoke action을 제공한다', async () => {
  window.history.pushState({}, '', '/admin/accounts');
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(jsonResponse({
      accountId: '00000000-0000-0000-0000-000000000001', loginId: 'admin', displayName: '관리자',
      roles: ['ADMIN'], permissions: ['ADMIN_ACCOUNT_MANAGE', 'ADMIN_AUDIT_READ'],
    }))
    .mockResolvedValueOnce(jsonResponse([{
      accountId: '00000000-0000-0000-0000-000000000002', loginId: 'viewer', displayName: '조회자',
      enabled: true, lockedUntil: null, roles: ['VIEWER'],
    }]));
  vi.stubGlobal('fetch', fetchMock);
  const { root, element } = await render();

  expect(element.textContent).toContain('viewer');
  expect(element.textContent).toContain('VIEWER');
  expect(element.querySelector('button[aria-label="viewer 세션 해제"]')).not.toBeNull();
  expect(fetchMock).toHaveBeenCalledWith('/api/v1/admin/accounts', expect.objectContaining({ credentials: 'same-origin' }));
  await act(async () => root.unmount());
});

it('감사 route에서 보안 이벤트를 표시한다', async () => {
  window.history.pushState({}, '', '/admin/audit');
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(jsonResponse({
      accountId: '00000000-0000-0000-0000-000000000001', loginId: 'admin', displayName: '관리자',
      roles: ['ADMIN'], permissions: ['ADMIN_AUDIT_READ'],
    }))
    .mockResolvedValueOnce(jsonResponse([{
      id: 10, actorAccountId: '00000000-0000-0000-0000-000000000001',
      targetAccountId: '00000000-0000-0000-0000-000000000002', eventType: 'ROLES_CHANGED',
      requestId: 'request-10', success: true, createdAt: '2026-07-12T00:00:00Z',
    }]));
  vi.stubGlobal('fetch', fetchMock);
  const { root, element } = await render();

  expect(element.textContent).toContain('ROLES_CHANGED');
  expect(element.textContent).toContain('request-10');
  await act(async () => root.unmount());
});

it('새 계정을 생성한 뒤 password field를 지우고 목록을 다시 조회한다', async () => {
  window.history.pushState({}, '', '/admin/accounts');
  const created = { accountId: '00000000-0000-0000-0000-000000000003', loginId: 'operator', displayName: '운영자', enabled: true, lockedUntil: null, roles: ['OPERATOR'] };
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(jsonResponse({ accountId: '00000000-0000-0000-0000-000000000001', loginId: 'admin', displayName: '관리자', roles: ['ADMIN'], permissions: ['ADMIN_ACCOUNT_MANAGE'] }))
    .mockResolvedValueOnce(jsonResponse([]))
    .mockResolvedValueOnce(jsonResponse(created, 201))
    .mockResolvedValueOnce(jsonResponse([created]));
  vi.stubGlobal('fetch', fetchMock);
  const { root, element } = await render();
  const form = element.querySelector<HTMLFormElement>('form');
  setValue(element, 'input[name="loginId"]', 'operator');
  setValue(element, 'input[name="displayName"]', '운영자');
  setValue(element, 'input[name="password"]', '  long password  ');
  const role = element.querySelector<HTMLSelectElement>('select[name="role"]'); if (role) role.value = 'OPERATOR';

  await act(async () => { form?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); });
  await act(async () => { await Promise.resolve(); await Promise.resolve(); });

  expect(fetchMock).toHaveBeenCalledWith('/api/v1/admin/accounts', expect.objectContaining({
    method: 'POST', body: JSON.stringify({ loginId: 'operator', displayName: '운영자', password: '  long password  ', roles: ['OPERATOR'] }),
  }));
  expect(element.querySelector<HTMLInputElement>('input[name="password"]')?.value).toBe('');
  expect(element.textContent).toContain('operator');
  await act(async () => root.unmount());
});

it('좌표 route에서 대기 목록을 조회하고 actor나 access code 없이 override한다', async () => {
  window.history.pushState({}, '', '/admin/coordinates');
  document.cookie = 'XSRF-TOKEN=coordinate-csrf; path=/';
  const pending = [{
    parcelId: 11, complexId: 21, pnu: '1111010100100010000', aptSeq: 'apt-21',
    aptName: '테스트 단지', address: '서울시 테스트구', reason: 'PNU_COORDINATE_MISSING',
    tradeCount: 7, createdAt: '2026-07-12T00:00:00Z',
  }];
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(jsonResponse({
      accountId: '00000000-0000-0000-0000-000000000001', loginId: 'operator', displayName: '운영자',
      roles: ['OPERATOR'], permissions: ['COORDINATE_READ', 'COORDINATE_WRITE'],
    }))
    .mockResolvedValueOnce(jsonResponse(pending))
    .mockResolvedValueOnce(jsonResponse({ totalCount: 1, reasonCounts: { PNU_COORDINATE_MISSING: 1, SAME_PNU_MULTI_COMPLEX: 0, COMPLEX_DISPLAY_COORDINATE_MISSING: 0 } }))
    .mockResolvedValueOnce(jsonResponse({ pnu: pending[0].pnu, latitude: 37.5, longitude: 127.0, parcelUpdated: true }))
    .mockResolvedValueOnce(jsonResponse([]))
    .mockResolvedValueOnce(jsonResponse({ totalCount: 0, reasonCounts: { PNU_COORDINATE_MISSING: 0, SAME_PNU_MULTI_COMPLEX: 0, COMPLEX_DISPLAY_COORDINATE_MISSING: 0 } }));
  vi.stubGlobal('fetch', fetchMock);
  const { root, element } = await render();

  expect(element.textContent).toContain('테스트 단지');
  const select = element.querySelector<HTMLButtonElement>(`button[data-pnu="${pending[0].pnu}"]`);
  await act(async () => select?.click());
  setValue(element, 'input[name="latitude"]', '37.5');
  setValue(element, 'input[name="longitude"]', '127.0');
  setValue(element, 'textarea[name="reason"]', '공식 좌표 확인');
  await act(async () => element.querySelector<HTMLFormElement>('form[aria-label="좌표 override"]')?.requestSubmit());
  await act(async () => { await Promise.resolve(); await Promise.resolve(); });

  const mutation = fetchMock.mock.calls.find(([path]) => String(path).endsWith('/override'));
  expect(mutation?.[1]).toEqual(expect.objectContaining({
    method: 'PUT',
    body: JSON.stringify({ latitude: 37.5, longitude: 127, reason: '공식 좌표 확인' }),
    credentials: 'same-origin',
  }));
  const headers = new Headers(mutation?.[1]?.headers);
  expect(headers.get('X-XSRF-TOKEN')).toBe('coordinate-csrf');
  expect(headers.has(['X-Admin', 'Access-Code'].join('-'))).toBe(false);
  expect(String(mutation?.[1]?.body)).not.toMatch(/actor|approvedBy/);
  await act(async () => root.unmount());
});

it('metadata route에서 기존 pending과 alias만 제공하고 retry actor를 전송하지 않는다', async () => {
  window.history.pushState({}, '', '/admin/metadata');
  const pending = [{ complexId: 31, aptName: '메타 단지', aptSeq: 'apt-31', canonicalPnu: '1111010100100020000', address: null, status: 'FAILED', failureKind: 'NOT_FOUND', failureReason: '원천 미조회', attempts: 2, nextAttemptAt: null, holdAt: null, holdReason: null }];
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(jsonResponse({
      accountId: '00000000-0000-0000-0000-000000000001', loginId: 'operator', displayName: '운영자',
      roles: ['OPERATOR'], permissions: ['METADATA_READ', 'METADATA_RETRY', 'METADATA_HOLD', 'METADATA_ALIAS_MANAGE'],
    }))
    .mockResolvedValueOnce(jsonResponse(pending))
    .mockResolvedValueOnce(jsonResponse({ totalCount: 1, statusCounts: { FAILED: 1 } }))
    .mockResolvedValueOnce(jsonResponse([{ id: 41, canonicalPrefix: '11110101', sourcePrefix: '11110102', status: 'PROPOSED', reason: '행정구역 변경', approvedBy: null, approvedAt: null, disabledBy: null, disabledAt: null }]))
    .mockResolvedValueOnce(new Response(null, { status: 204 }))
    .mockResolvedValueOnce(jsonResponse([]))
    .mockResolvedValueOnce(jsonResponse({ totalCount: 0, statusCounts: {} }))
    .mockResolvedValueOnce(jsonResponse([]));
  vi.stubGlobal('fetch', fetchMock);
  const { root, element } = await render();

  expect(element.textContent).toContain('메타 단지');
  expect(element.textContent).toContain('11110101 → 11110102');
  await act(async () => element.querySelector<HTMLButtonElement>('button[aria-label="메타 단지 재시도"]')?.click());
  await act(async () => { await Promise.resolve(); await Promise.resolve(); });

  const mutation = fetchMock.mock.calls.find(([path]) => String(path).endsWith('/31/retry'));
  expect(mutation?.[1]).toEqual(expect.objectContaining({ method: 'POST', body: JSON.stringify({ reason: '관리자 재시도 요청' }) }));
  expect(String(mutation?.[1]?.body)).not.toContain('actor');
  expect(fetchMock.mock.calls.some(([path]) => String(path).includes(['/metadata', 'building/'].join('/')))).toBe(false);
  await act(async () => root.unmount());
});

it('좌표 사유 route는 변경 작업 없이 사유별 운영 원칙을 보여준다', async () => {
  window.history.pushState({}, '', '/admin/coordinates/reasons');
  const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse({
    accountId: '00000000-0000-0000-0000-000000000001', loginId: 'viewer', displayName: '조회자',
    roles: ['VIEWER'], permissions: ['COORDINATE_READ'],
  }));
  vi.stubGlobal('fetch', fetchMock);
  const { root, element } = await render();

  expect(element.textContent).toContain('동일 PNU 다중 단지');
  expect(element.textContent).toContain('parcel 좌표를 덮어쓰지 않습니다');
  expect(fetchMock).toHaveBeenCalledTimes(1);
  await act(async () => root.unmount());
});

async function render(): Promise<{root: Root; element: HTMLDivElement}> {
  const element = document.createElement('div');
  document.body.appendChild(element);
  const root = createRoot(element);
  await act(async () => root.render(<AdminApp />));
  await act(async () => { await Promise.resolve(); await Promise.resolve(); });
  return { root, element };
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function setValue(element: HTMLElement, selector: string, value: string) {
  const input = element.querySelector<HTMLInputElement>(selector); if (input) input.value = value;
}
