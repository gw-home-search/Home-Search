import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, describe, expect, it } from 'vitest';

import type { BuildingProfile } from './api/fetchComplexDetail';
import { BuildingProfilePanel } from './BuildingProfilePanel';

describe('BuildingProfilePanel', () => {
  let host: HTMLDivElement | null = null;

  afterEach(() => {
    host?.remove();
    host = null;
  });

  it('생활정보 valid zero와 fallback/partial badge를 표시하고 안전·에너지를 details로 접는다', () => {
    host = document.createElement('div');
    document.body.append(host);
    const root = createRoot(host);
    const profile: BuildingProfile = {
      ratios: null,
      households: { scope: 'COMPLEX', quality: 'VERIFIED', householdCount: 410, familyCount: 0, unitCount: 420 },
      parking: { scope: 'PARCEL', quality: 'PNU_FALLBACK', totalCount: 0, perHousehold: null,
        indoorMechanicalCount: 0, indoorMechanicalAreaM2: 120.5, outdoorMechanicalCount: null,
        outdoorMechanicalAreaM2: null, indoorAutomaticCount: 12, indoorAutomaticAreaM2: null,
        outdoorAutomaticCount: null, outdoorAutomaticAreaM2: null },
      building: { scope: 'COMPLEX', quality: 'PARTIAL', mainBuildingCount: 4, attachedBuildingCount: 1,
        maxGroundFloorCount: 28, maxUndergroundFloorCount: 3, maxHeightM: 91.2,
        structures: ['철근콘크리트'], roofs: ['평지붕'], primaryUses: ['공동주택'] },
      elevators: { scope: 'COMPLEX', quality: 'VERIFIED', rideUseCount: 8, emergencyUseCount: 2 },
      safety: { scope: 'COMPLEX', quality: 'VERIFIED', seismicDesignStatus: 'ALL_APPLIED', seismicAbilities: ['VII-0.2g'] },
      dates: { scope: 'COMPLEX', quality: 'VERIFIED', permitDate: '2015-01-01',
        constructionStartDate: '2016-02-02', useApprovalDate: '2019-03-03' },
      address: { scope: 'PARCEL', quality: 'PNU_FALLBACK', parcelAddress: '지번', roadAddress: '테헤란로 1' },
      energy: { scope: 'COMPLEX', quality: 'PARTIAL', efficiencyGrades: ['1등급'], savingRateMin: null,
        savingRateMax: null, epiMin: null, epiMax: null, greenGrades: [], greenScoreMin: null,
        greenScoreMax: null, intelligentGrades: [], intelligentScoreMin: null, intelligentScoreMax: null },
    };

    act(() => root.render(<BuildingProfilePanel profile={profile} />));

    expect(host.textContent).toContain('0가구');
    expect(host.textContent).toContain('0대');
    expect(host.textContent).toContain('120.5㎡');
    expect(host.textContent).toContain('주 건물 4동');
    expect(host.textContent).toContain('평지붕');
    expect(host.textContent).toContain('테헤란로 1');
    expect(host.textContent).toContain('대지 기준');
    expect(host.textContent).toContain('확인된 동 기준');
    expect(host.querySelector('details summary')?.textContent).toContain('안전·에너지');
    expect(host.textContent).toContain('1등급');
    act(() => root.unmount());
  });
});
