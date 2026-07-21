import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';

import { ChatArtifacts } from './ChatArtifacts';
import type { ComparisonTableArtifact } from './artifactContract';

describe('비교표 artifact UI', () => {
  it('실제 table header와 확인 불가 이유·동일 기준을 표시한다', () => {
    const artifact: ComparisonTableArtifact = {
      type: 'comparisonTable',
      version: 1,
      artifactId: 'comparison-501-502',
      title: '동일 기준 단지 비교',
      columns: [
        { key: '501', label: '잠실엘스', factIds: ['complex-501'] },
        { key: '502', label: '헬리오시티', factIds: ['complex-502'] },
      ],
      rows: [{
        key: 'latestTrade',
        label: '가장 최근 거래',
        cells: [
          {
            availability: 'available',
            value: '2026-07-20 · 20억원',
            unit: '10_000_KRW',
            reason: null,
            factIds: ['basis-501'],
          },
          {
            availability: 'unavailable',
            value: null,
            unit: '10_000_KRW',
            reason: '동일 면적의 최근 거래 표본이 3건 미만입니다.',
            factIds: ['basis-502'],
          },
        ],
      }],
      basis: {
        cutoffDate: '2026-07-20',
        startDate: '2025-07-21',
        exclusiveAreaSquareMeters: 84,
      },
    };

    const html = renderToStaticMarkup(<ChatArtifacts artifacts={[artifact]} />);

    expect(html).toContain('<th scope="col">잠실엘스</th>');
    expect(html).toContain('<th scope="row">가장 최근 거래</th>');
    expect(html).toContain('확인 불가 · 동일 면적의 최근 거래 표본이 3건 미만입니다.');
    expect(html).toContain('기준일 2026-07-20 · 최근 365일 · 전용면적');
    expect(html).toContain('tabindex="0"');
  });
});
