import { describe, expect, it } from 'vitest';

import { readChatActions } from './actionContract';

describe('챗봇 지도 action 계약', () => {
  it('검증된 단지 fact에 연결된 병원·어린이집 action만 읽는다', () => {
    const actions = readChatActions([
      {
        type: 'showNearbyCategory',
        version: 1,
        actionId: 'action-1',
        label: '지도에서 병원 보기',
        category: 'HOSPITAL',
        center: { lat: 37.513, lng: 127.082 },
        level: 4,
        factIds: ['property-complex-501'],
      },
      {
        type: 'showNearbyCategory',
        version: 1,
        actionId: 'action-2',
        label: '지도에서 카페 보기',
        category: 'CAFE',
        center: { lat: 37.513, lng: 127.082 },
        level: 4,
        factIds: ['property-complex-501'],
      },
      {
        type: 'futureAction',
        version: 1,
        actionId: 'future-1',
      },
    ], new Set(['property-complex-501']));

    expect(actions).toEqual([{
      type: 'showNearbyCategory',
      version: 1,
      actionId: 'action-1',
      label: '지도에서 병원 보기',
      category: 'HOSPITAL',
      center: { lat: 37.513, lng: 127.082 },
      level: 4,
      factIds: ['property-complex-501'],
    }]);
  });

  it('존재하지 않는 fact·비정상 좌표·중복 actionId를 무시한다', () => {
    const base = {
      type: 'showNearbyCategory',
      version: 1,
      actionId: 'action-1',
      label: '지도에서 어린이집 보기',
      category: 'DAYCARE_KINDERGARTEN',
      center: { lat: 37.513, lng: 127.082 },
      level: 4,
      factIds: ['property-complex-501'],
    };

    expect(readChatActions([
      { ...base, factIds: ['unknown-fact'] },
      { ...base, center: { lat: Number.NaN, lng: 127.082 } },
      base,
      { ...base, label: '중복' },
    ], new Set(['property-complex-501']))).toEqual([base]);
  });

  it('응답당 action을 계약 상한 4개로 제한한다', () => {
    const actions = Array.from({ length: 5 }, (_, index) => ({
      type: 'showNearbyCategory',
      version: 1,
      actionId: `action-${index + 1}`,
      label: '지도에서 병원 보기',
      category: 'HOSPITAL',
      center: { lat: 37.513, lng: 127.082 },
      level: 4,
      factIds: ['property-complex-501'],
    }));

    expect(readChatActions(actions, new Set(['property-complex-501']))).toHaveLength(4);
  });
});
