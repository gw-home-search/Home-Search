import { describe, expect, it } from 'vitest';

import { readChatArtifacts } from './artifactContract';

describe('comparisonTable/v1 계약', () => {
  it('2개 단지의 available/unavailable cell과 field별 factIds를 읽는다', () => {
    const artifact = {
      type: 'comparisonTable',
      version: 1,
      artifactId: 'comparison-501-502',
      title: '동일 기준 단지 비교',
      columns: [
        { key: '501', label: '잠실엘스', factIds: ['property-complex-501'] },
        { key: '502', label: '헬리오시티', factIds: ['property-complex-502'] },
      ],
      rows: [{
        key: 'latestTrade',
        label: '가장 최근 거래',
        cells: [
          {
            availability: 'available',
            value: '2026-07-20 · 20억 5,000만원',
            unit: '10_000_KRW',
            reason: null,
            factIds: ['comparison-trade-basis-501'],
          },
          {
            availability: 'unavailable',
            value: null,
            unit: '10_000_KRW',
            reason: '동일 면적의 최근 거래 표본이 3건 미만입니다.',
            factIds: ['comparison-trade-basis-502'],
          },
        ],
      }],
      basis: {
        cutoffDate: '2026-07-20',
        startDate: '2025-07-21',
        exclusiveAreaSquareMeters: 84,
      },
    };

    expect(readChatArtifacts(
      [artifact],
      new Set([
        'property-complex-501',
        'property-complex-502',
        'comparison-trade-basis-501',
        'comparison-trade-basis-502',
      ]),
    )).toEqual([artifact]);
  });

  it('column 상한·cell 수·존재하지 않는 factId를 위반하면 표만 무시한다', () => {
    const column = (index: number) => ({
      key: String(index),
      label: `단지 ${index}`,
      factIds: [`complex-${index}`],
    });
    const base = {
      type: 'comparisonTable',
      version: 1,
      artifactId: 'comparison-invalid',
      title: '동일 기준 단지 비교',
      columns: [column(1), column(2)],
      rows: [{
        key: 'latestTrade',
        label: '가장 최근 거래',
        cells: [{
          availability: 'available',
          value: '20억원',
          unit: '10_000_KRW',
          reason: null,
          factIds: ['unknown-fact'],
        }],
      }],
      basis: {
        cutoffDate: '2026-07-20',
        startDate: '2025-07-21',
        exclusiveAreaSquareMeters: 84,
      },
    };

    expect(readChatArtifacts([base], new Set(['complex-1', 'complex-2']))).toEqual([]);
    expect(readChatArtifacts([
      { ...base, columns: [column(1), column(2), column(3), column(4), column(5)] },
    ], new Set([
      'complex-1', 'complex-2', 'complex-3', 'complex-4', 'complex-5', 'unknown-fact',
    ]))).toEqual([]);
  });
});
