import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';

import { ChatArtifacts } from './ChatArtifacts';
import type {
  ComparisonTableArtifact,
  RecommendationCardsArtifact,
} from './artifactContract';

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

describe('추천카드 artifact UI', () => {
  it('핵심 거래 기준과 펼칠 수 있는 결정론적 점수 근거를 표시한다', () => {
    const artifact: RecommendationCardsArtifact = {
      type: 'recommendationCards',
      version: 1,
      artifactId: 'recommendation-2026-07-20-84-200000',
      title: '조건을 충족한 단지',
      policyVersion: 'recommendation-policy-v1',
      cards: [{
        rank: 1,
        complexId: 501,
        complexName: '잠실엘스',
        totalScore: 92.5,
        latestTrade: {
          date: '2026-07-20', amountTenThousandKrw: 195000, factIds: ['trade-501'],
        },
        recentThreeMedian: { amountTenThousandKrw: 190000, factIds: ['trade-501'] },
        scoreBreakdown: [
          { key: 'PRICE', label: '예산 조건', weight: 60, points: 60, distanceMeters: null, factIds: ['trade-501'] },
          { key: 'TRANSIT', label: '철도 접근성', weight: 25, points: 20, distanceMeters: 300, factIds: ['rail-501'] },
          { key: 'SHOPPING', label: '대규모점포 접근성', weight: 15, points: 12.5, distanceMeters: 167, factIds: ['retail-501'] },
        ],
        limitations: ['가격 통과 후보에는 추가 가격 가산점이 없습니다.'],
        factIds: ['complex-501', 'trade-501', 'rail-501', 'retail-501'],
        activeThemes: [],
      }],
    };

    const html = renderToStaticMarkup(<ChatArtifacts artifacts={[artifact]} />);

    expect(html).toContain('1. 잠실엘스');
    expect(html).toContain('조건 충족도 92.5점');
    expect(html).toContain('2026-07-20 · 19억 5,000만원');
    expect(html).toContain('최근 3건 중앙값');
    expect(html).toContain('<summary>점수 근거</summary>');
    expect(html).toContain('20 / 25점 · 300m');
    expect(html).not.toContain('투자');
  });

  it('명시적으로 반영한 생활조건 badge를 표시한다', () => {
    const artifact: RecommendationCardsArtifact = {
      type: 'recommendationCards', version: 1, artifactId: 'recommendation-themed',
      title: '조건을 충족한 단지', policyVersion: 'recommendation-policy-v1',
      cards: [{
        rank: 1, complexId: 1, complexName: '후보 1', totalScore: 100,
        latestTrade: { date: '2026-07-20', amountTenThousandKrw: 190000, factIds: ['trade'] },
        recentThreeMedian: { amountTenThousandKrw: 190000, factIds: ['trade'] },
        scoreBreakdown: [
          { key: 'PRICE', label: '예산 조건', weight: 60, points: 60, distanceMeters: null, factIds: ['trade'] },
          { key: 'TRANSIT', label: '철도 접근성', weight: 22.5, points: 22.5, distanceMeters: 0, factIds: ['rail'] },
          { key: 'SHOPPING', label: '대규모점포 접근성', weight: 5, points: 5, distanceMeters: 0, factIds: ['retail'] },
          { key: 'STUDENT', label: '학생 조건', weight: 12.5, points: 12.5, distanceMeters: null, factIds: ['student'], details: ['ELEMENTARY: 가까운초등학교 300m', '800m 내 Sbiz 교육업소 5곳'] },
        ],
        limitations: [], factIds: ['complex', 'trade', 'rail', 'retail', 'student'],
        activeThemes: ['TRANSIT', 'STUDENT'],
      }],
    };

    const html = renderToStaticMarkup(<ChatArtifacts artifacts={[artifact]} />);
    expect(html).toContain('aria-label="반영한 생활조건"');
    expect(html).toContain('>교통<');
    expect(html).toContain('>학생<');
    expect(html).toContain('800m 내 Sbiz 교육업소 5곳');
  });
});
