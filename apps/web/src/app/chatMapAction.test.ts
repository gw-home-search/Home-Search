import { describe, expect, it, vi } from 'vitest';

import type { FocusComplexAction } from '../features/chat/actionContract';
import { executeFocusComplexAction } from './chatMapAction';

describe('챗봇 단지 지도 이동', () => {
  it('같은 parcel과 좌표에서도 A→B→A complex 선택을 모두 실행한다', () => {
    const focusMap = vi.fn();
    const selectComplex = vi.fn();
    const action = (complexId: number): FocusComplexAction => ({
      type: 'focusComplex', version: 1,
      actionId: `action-request-focus-complex-${complexId}`,
      label: `단지 ${complexId} 지도에서 보기`, parcelId: 8015, complexId,
      center: { lat: 37.5555141, lng: 126.9537536 }, level: 4,
      openDetail: true, autoRun: complexId === 7756,
      factIds: [`property-complex-${complexId}`],
    });

    for (const complexId of [7753, 7756, 7753]) {
      executeFocusComplexAction(action(complexId), focusMap, selectComplex, 0.00001);
    }

    expect(focusMap).toHaveBeenCalledTimes(3);
    expect(selectComplex.mock.calls.map(([selection]) => selection)).toEqual([
      { parcelId: 8015, complexId: 7753 },
      { parcelId: 8015, complexId: 7756 },
      { parcelId: 8015, complexId: 7753 },
    ]);
  });
});
