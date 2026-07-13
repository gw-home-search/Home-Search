import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { ComplexList } from './ComplexList';

describe('ComplexList 단지 목록', () => {
  let root: Root | null = null;

  afterEach(() => {
    if (root) {
      act(() => root?.unmount());
      root = null;
    }
  });

  it('검색 결과와 지역 단지가 공유할 수 있는 비교형 행을 렌더링한다', async () => {
    const host = document.createElement('div');
    const onSelect = vi.fn();
    root = createRoot(host);

    await act(async () => {
      root?.render(
        <ComplexList
          ariaLabel="검색 결과"
          items={[
            {
              id: 501,
              ariaLabel: '검색 결과 선택 평동 동남',
              name: '평동 동남',
              address: '평동 151',
              onSelect,
            },
            {
              id: 502,
              ariaLabel: '지역 단지 선택 신동아',
              name: '신동아',
              address: '응봉동 275',
              approvalYear: '1996',
              unitCount: 434,
              buildingCount: 4,
              onSelect,
            },
          ]}
        />,
      );
    });

    const rows = host.querySelectorAll<HTMLButtonElement>('.complex-list-row');
    expect(host.querySelector('ul')?.getAttribute('aria-label')).toBe('검색 결과');
    expect(rows).toHaveLength(2);
    expect(rows[0]?.querySelector('.complex-list-name')?.textContent).toBe('평동 동남');
    expect(rows[0]?.querySelector('.complex-list-address')?.textContent).toBe('평동 151');
    expect(rows[1]?.querySelector('.complex-list-approval')?.textContent).toBe('· 1996년 승인');
    expect(rows[1]?.querySelector('.complex-list-unit')?.textContent).toBe('434세대');
    expect(rows[1]?.querySelector('.complex-list-building')?.textContent).toBe('4동');

    act(() => rows[0]?.click());
    expect(onSelect).toHaveBeenCalledOnce();
  });
});
