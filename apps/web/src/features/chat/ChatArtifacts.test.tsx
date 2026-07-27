import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';

import { ChatArtifacts } from './ChatArtifacts';
import type {
  ComparisonTableArtifact,
  CandidateProfileArtifact,
  RecommendationCardsArtifact,
  RecommendationTableArtifact,
  TradeTableArtifact,
  TrendTableArtifact,
} from './artifactContract';
import { readChatArtifacts } from './artifactContract';

describe('조회표 artifact UI', () => {
  it('실거래와 월별 관찰값을 각각 고정 표 component로 표시한다', () => {
    const trade: TradeTableArtifact = {
      type: 'tradeTable', version: 1, artifactId: 'trade-table-1',
      title: '최근 실거래', amountUnit: '10_000_KRW',
      rows: [{
        tradeId: 1, dealDate: '2026-07-15', exclusiveAreaSquareMeters: 84.8,
        amountTenThousandKrw: 250000, floor: 12, factIds: ['trade-1'],
      }],
    };
    const trend: TrendTableArtifact = {
      type: 'trendTable', version: 1, artifactId: 'trend-table-1',
      title: '월별 가격 관찰값', amountUnit: '10_000_KRW',
      rows: [{
        month: '2026-06', averageAmountTenThousandKrw: 245000,
        minimumAmountTenThousandKrw: 240000, maximumAmountTenThousandKrw: 250000,
        tradeCount: 3, availability: 'available', reason: null,
        factIds: ['trend-1'],
      }],
    };

    const html = renderToStaticMarkup(<ChatArtifacts artifacts={[trade, trend]} />);

    expect(html).toContain('25억원');
    expect(html).toContain('2026-06');
    expect(html).toContain('3건');
    expect(html).toContain('chatbot-trade-table');
    expect(html).toContain('chatbot-trend-table');
  });
});

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

  it('면적 없는 v2 비교표는 가격을 만들지 않고 비교 group과 기준을 표시한다', () => {
    const artifact: ComparisonTableArtifact = {
      type: 'comparisonTable', version: 2, artifactId: 'comparison-501-502',
      title: '확인 가능한 기준으로 단지 비교',
      columns: [
        { key: '501', label: '잠실엘스', factIds: ['complex-501'] },
        { key: '502', label: '헬리오시티', factIds: ['complex-502'] },
      ],
      rows: [
        { key: 'unitCount', label: '세대수', group: 'SCALE', cells: [
          { availability: 'available', value: '5,000세대', unit: 'COUNT', reason: null, factIds: ['complex-501'] },
          { availability: 'available', value: '9,510세대', unit: 'COUNT', reason: null, factIds: ['complex-502'] },
        ] },
        { key: 'nearestRail', label: '최근접 철도역', group: 'TRANSPORT', cells: [
          { availability: 'available', value: '잠실역 420m', unit: 'METERS', reason: null, factIds: ['rail-501'] },
          { availability: 'available', value: '송파역 310m', unit: 'METERS', reason: null, factIds: ['rail-502'] },
        ] },
      ],
      basis: { cutoffDate: null, startDate: null, exclusiveAreaSquareMeters: null },
    };

    const html = renderToStaticMarkup(<ChatArtifacts artifacts={[artifact]} />);

    expect(html).toContain('기본정보');
    expect(html).toContain('교통');
    expect(html).not.toContain('>가격<');
    expect(html).toContain('면적 조건이 없어 가격을 제외하고');
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
          { key: 'STUDENT', label: '학생 조건', weight: 12.5, points: 12.5, distanceMeters: null, factIds: ['student'], details: ['ELEMENTARY: 가까운초등학교 300m', '800m 내 학원 위치 5곳'] },
        ],
        limitations: [], factIds: ['complex', 'trade', 'rail', 'retail', 'student'],
        activeThemes: ['TRANSIT', 'STUDENT'],
      }],
    };

    const html = renderToStaticMarkup(<ChatArtifacts artifacts={[artifact]} />);
    expect(html).toContain('aria-label="반영한 생활조건"');
    expect(html).toContain('>교통<');
    expect(html).toContain('>학생<');
    expect(html).toContain('800m 내 학원 위치 5곳');
  });
});

describe('조건 추천표 artifact UI', () => {
  it('세대수와 요청한 고정 metric만 표로 표시한다', () => {
    const artifact: RecommendationTableArtifact = {
      type: 'recommendationTable', version: 1,
      artifactId: 'criteria-recommendation-2026-07-20-500-academy',
      title: '조건 기반 후보', policyVersion: 'criteria-recommendation-policy-v1',
      basis: {
        scopeType: 'ADMIN_REGION', scopeLabel: '영등포구',
        criteriaOrder: ['ACADEMY'], minimumUnitCount: 500, radiusMeters: 800,
      },
      rows: [{
        order: 1, complexId: 503, complexName: '후보 503', unitCount: 1200,
        metrics: {
          ACADEMY: {
            availability: 'available', value: 10, unit: 'COUNT',
            nearestDistanceMeters: 100, reason: null, factIds: ['academy-503'],
          },
        },
        factIds: ['complex-503', 'academy-503'],
      }],
    };

    const html = renderToStaticMarkup(<ChatArtifacts artifacts={[artifact]} />);

    expect(html).toContain('1. 후보 503');
    expect(html).toContain('1,200세대');
    expect(html).toContain('10곳 · 최근접 100m');
    expect(html).toContain('영등포구 · 최소 500세대');
    expect(html).not.toContain('점수');
  });

  it('허용하지 않은 metric이나 근거 id가 있으면 artifact를 제외한다', () => {
    const base = {
      type: 'recommendationTable', version: 1, artifactId: 'criteria-invalid',
      title: '조건 기반 후보', policyVersion: 'criteria-recommendation-policy-v1',
      basis: {
        scopeType: 'ADMIN_REGION', scopeLabel: '영등포구',
        criteriaOrder: ['ACADEMY'], minimumUnitCount: 500, radiusMeters: 800,
      },
      rows: [{
        order: 1, complexId: 503, complexName: '후보 503', unitCount: 1200,
        metrics: {
          CHILDCARE: {
            availability: 'available', value: 10, unit: 'COUNT',
            nearestDistanceMeters: 100, reason: null, factIds: ['childcare-503'],
          },
        },
        factIds: ['complex-503', 'childcare-503'],
      }],
    };

    expect(readChatArtifacts(
      [base], new Set(['complex-503', 'childcare-503']),
    )).toEqual([]);
  });

  it('v2는 후보·강점·tradeoff를 한 흐름에서 한 번씩 표시한다', () => {
    const wire = {
      type: 'recommendationTable', version: 2, artifactId: 'agentic-v2',
      title: 'AI 근거 비교 후보', policyVersion: 'agentic-recommendation-v1',
      basis: {
        selectionMode: 'AGENTIC', scopeType: 'ADMIN_REGION', scopeLabel: '송파구',
        requestedCount: 2, criteriaOrder: [], defaultPolicy: 'BALANCED_V1',
      },
      rows: [{
        order: 1, complexId: 20, complexName: '나단지', role: 'BALANCED',
        summary: '거래와 규모를 함께 비교했습니다.',
        strengths: [{ text: '규모가 확인됩니다.', factIds: ['complex-20'] }],
        tradeoffs: [{ text: '예산 적합성은 추가 확인이 필요합니다.', factIds: ['complex-20'] }],
        metrics: {}, factIds: ['complex-20'],
      }, {
        order: 2, complexId: 10, complexName: '가단지', role: 'NEWER',
        summary: '사용승인일을 함께 확인했습니다.',
        strengths: [{ text: '상대적으로 최근 사용승인입니다.', factIds: ['complex-10'] }],
        tradeoffs: [{ text: '최근 거래는 추가 확인이 필요합니다.', factIds: ['complex-10'] }],
        metrics: {}, factIds: ['complex-10'],
      }],
    };

    const parsed = readChatArtifacts([wire], new Set(['complex-20', 'complex-10']));
    const html = renderToStaticMarkup(<ChatArtifacts artifacts={parsed} />);

    expect(parsed).toHaveLength(1);
    expect(html.match(/1\. 나단지/g)).toHaveLength(1);
    expect(html.match(/2\. 가단지/g)).toHaveLength(1);
    expect(html.match(/규모가 확인됩니다\./g)).toHaveLength(1);
    expect(html).toContain('적용 기준 · 송파구 · 균형 비교(BALANCED_V1)');
    expect(html).not.toContain('조건 기반 후보');
    expect(html).not.toContain('먼저 볼 후보');
  });

  it('v2에 unknown field나 알 수 없는 factId가 있으면 text answer용으로 무시한다', () => {
    const base = {
      type: 'recommendationTable', version: 2, artifactId: 'agentic-invalid',
      title: 'AI 근거 비교 후보', policyVersion: 'agentic-recommendation-v1',
      basis: {
        selectionMode: 'AGENTIC', scopeType: 'ADMIN_REGION', scopeLabel: '송파구',
        requestedCount: 1, criteriaOrder: [], defaultPolicy: 'BALANCED_V1',
      },
      rows: [{
        order: 1, complexId: 20, complexName: '나단지', role: 'BALANCED',
        summary: '검증 근거 비교',
        strengths: [{ text: '확인된 강점', factIds: ['unknown-fact'] }],
        tradeoffs: [{ text: '확인된 tradeoff', factIds: ['complex-20'] }],
        metrics: {}, factIds: ['complex-20'],
      }],
    };

    expect(readChatArtifacts([base], new Set(['complex-20']))).toEqual([]);
    expect(readChatArtifacts(
      [{ ...base, unexpected: true }], new Set(['complex-20', 'unknown-fact']),
    )).toEqual([]);
  });
});

describe('후보 상세 artifact UI', () => {
  it('첫 후보만 펼치고 확인된 섹션만 보여준다', () => {
    const profiles: CandidateProfileArtifact[] = [{
      type: 'candidateProfile', version: 1, artifactId: 'candidate-profile-503',
      title: '후보 503', rank: 1, complexId: 503,
      address: '서울 영등포구 후보 503', unitCount: 1200, useDate: '2018-03-01',
      reasons: [{ text: '주변 학원 수가 후보 중 가장 많습니다.', factIds: ['academy-503'] }],
      sections: [{
        key: 'EDUCATION', label: '교육',
        items: [{ label: '학원 접근성', value: '800m 내 10곳 · 최근접 100m', factIds: ['academy-503'] }],
      }],
      factIds: ['complex-503', 'academy-503'],
    }, {
      type: 'candidateProfile', version: 1, artifactId: 'candidate-profile-502',
      title: '후보 502', rank: 2, complexId: 502,
      address: '서울 영등포구 후보 502', unitCount: 800, useDate: null,
      reasons: [{ text: '주변 학원 접근성을 함께 비교할 후보입니다.', factIds: ['academy-502'] }],
      sections: [], factIds: ['complex-502', 'academy-502'],
    }];

    const html = renderToStaticMarkup(<ChatArtifacts artifacts={profiles} />);

    expect(html).toContain('candidate-profile-503');
    expect(html).toContain('open=""');
    expect((html.match(/open=""/g) ?? [])).toHaveLength(1);
    expect(html).toContain('주변 학원 수가 후보 중 가장 많습니다.');
    expect(html).not.toContain('Sbiz');
    expect(readChatArtifacts(
      [{ ...profiles[0], version: 2 }],
      new Set(['complex-503', 'academy-503']),
    )).toEqual([]);
  });
});
