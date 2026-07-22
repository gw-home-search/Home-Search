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
        activeThemes: [],
      }],
    };

    expect(readChatArtifacts(
      [artifact],
      new Set(artifact.cards[0].factIds),
    )).toEqual([artifact]);
  });

  it('S7에서 저장된 activeThemes 없는 추천 카드를 기본 theme로 읽는다', () => {
    const factIds = ['complex-1', 'trade-1', 'rail-1', 'retail-1'];
    const artifact = {
      type: 'recommendationCards', version: 1, artifactId: 'recommendation-legacy',
      title: '조건을 충족한 단지', policyVersion: 'recommendation-policy-v1',
      cards: [{
        rank: 1, complexId: 1, complexName: '후보 1', totalScore: 100,
        latestTrade: { date: '2026-07-20', amountTenThousandKrw: 190000, factIds: ['trade-1'] },
        recentThreeMedian: { amountTenThousandKrw: 190000, factIds: ['trade-1'] },
        scoreBreakdown: [
          { key: 'PRICE', label: '예산 조건', weight: 60, points: 60, distanceMeters: null, factIds: ['trade-1'] },
          { key: 'TRANSIT', label: '철도 접근성', weight: 25, points: 25, distanceMeters: 0, factIds: ['rail-1'] },
          { key: 'SHOPPING', label: '대규모점포 접근성', weight: 15, points: 15, distanceMeters: 0, factIds: ['retail-1'] },
        ],
        limitations: [], factIds,
      }],
    };

    const [parsed] = readChatArtifacts([artifact], new Set(factIds));
    expect(parsed).toMatchObject({
      type: 'recommendationCards',
      cards: [{ activeThemes: [] }],
    });
  });

  it('잘못된 policyVersion이나 존재하지 않는 factId가 있으면 카드만 무시한다', () => {
    const artifact = {
      type: 'recommendationCards', version: 1, artifactId: 'recommendation-invalid',
      title: '조건을 충족한 단지', policyVersion: 'model-generated-policy',
      cards: [{
        rank: 1, complexId: 1, complexName: '단지', totalScore: 100,
        latestTrade: { date: '2026-07-20', amountTenThousandKrw: 100000, factIds: ['unknown'] },
        recentThreeMedian: { amountTenThousandKrw: 100000, factIds: ['unknown'] },
        scoreBreakdown: [], limitations: [], factIds: ['unknown'], activeThemes: [],
      }],
    };

    expect(readChatArtifacts([artifact], new Set(['known']))).toEqual([]);
  });

  it('명시된 학생·교통 theme의 동적 25점 분배만 허용한다', () => {
    const factIds = ['complex-1', 'trade-1', 'rail-1', 'retail-1', 'student-1'];
    const artifact = {
      type: 'recommendationCards', version: 1, artifactId: 'recommendation-themed',
      title: '조건을 충족한 단지', policyVersion: 'recommendation-policy-v1',
      cards: [{
        rank: 1, complexId: 1, complexName: '후보 1', totalScore: 100,
        latestTrade: { date: '2026-07-20', amountTenThousandKrw: 190000, factIds: ['trade-1'] },
        recentThreeMedian: { amountTenThousandKrw: 190000, factIds: ['trade-1'] },
        scoreBreakdown: [
          { key: 'PRICE', label: '예산 조건', weight: 60, points: 60, distanceMeters: null, factIds: ['trade-1'] },
          { key: 'TRANSIT', label: '철도 접근성', weight: 22.5, points: 22.5, distanceMeters: 0, factIds: ['rail-1'] },
          { key: 'SHOPPING', label: '대규모점포 접근성', weight: 5, points: 5, distanceMeters: 0, factIds: ['retail-1'] },
          { key: 'STUDENT', label: '학생 조건', weight: 12.5, points: 12.5, distanceMeters: null, factIds: ['student-1'], details: ['ELEMENTARY: 가까운초등학교 300m', '800m 내 Sbiz 교육업소 5곳'] },
        ],
        limitations: [], factIds, activeThemes: ['TRANSIT', 'STUDENT'],
      }],
    };

    expect(readChatArtifacts([artifact], new Set(factIds))).toEqual([artifact]);
    const invalid = structuredClone(artifact);
    invalid.cards[0].scoreBreakdown[1].weight = 25;
    expect(readChatArtifacts([invalid], new Set(factIds))).toEqual([]);
  });
});
