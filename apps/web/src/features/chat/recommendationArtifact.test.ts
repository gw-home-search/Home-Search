import { describe, expect, it } from 'vitest';

import { readChatArtifacts } from './artifactContract';

describe('recommendationCards/v1 계약', () => {
  it('결정론적 점수와 거래 근거가 있는 추천 카드를 읽는다', () => {
    const artifact = {
      type: 'recommendationCards',
      version: 1,
      artifactId: 'recommendation-songpa-84',
      title: '조건을 충족한 단지',
      policyVersion: 'recommendation-policy-v1',
      cards: [{
        rank: 1,
        complexId: 501,
        complexName: '잠실엘스',
        totalScore: 92.5,
        latestTrade: {
          date: '2026-07-20',
          amountTenThousandKrw: 195000,
          factIds: ['recommendation-trade-501'],
        },
        recentThreeMedian: {
          amountTenThousandKrw: 190000,
          factIds: ['recommendation-trade-501'],
        },
        scoreBreakdown: [
          { key: 'PRICE', label: '예산 조건', weight: 60, points: 60, distanceMeters: null, factIds: ['recommendation-trade-501'] },
          { key: 'TRANSIT', label: '철도 접근성', weight: 25, points: 20, distanceMeters: 300, factIds: ['recommendation-rail-501'] },
          { key: 'SHOPPING', label: '대규모점포 접근성', weight: 15, points: 12.5, distanceMeters: 167, factIds: ['recommendation-retail-501'] },
        ],
        limitations: ['최근 3건과 직선거리 기준입니다.'],
        factIds: ['property-complex-501', 'recommendation-trade-501', 'recommendation-rail-501', 'recommendation-retail-501'],
      }],
    };

    expect(readChatArtifacts(
      [artifact],
      new Set(artifact.cards[0].factIds),
    )).toEqual([artifact]);
  });

  it('잘못된 policyVersion이나 존재하지 않는 factId가 있으면 카드만 무시한다', () => {
    const artifact = {
      type: 'recommendationCards', version: 1, artifactId: 'recommendation-invalid',
      title: '조건을 충족한 단지', policyVersion: 'model-generated-policy',
      cards: [{
        rank: 1, complexId: 1, complexName: '단지', totalScore: 100,
        latestTrade: { date: '2026-07-20', amountTenThousandKrw: 100000, factIds: ['unknown'] },
        recentThreeMedian: { amountTenThousandKrw: 100000, factIds: ['unknown'] },
        scoreBreakdown: [], limitations: [], factIds: ['unknown'],
      }],
    };

    expect(readChatArtifacts([artifact], new Set(['known']))).toEqual([]);
  });
});
