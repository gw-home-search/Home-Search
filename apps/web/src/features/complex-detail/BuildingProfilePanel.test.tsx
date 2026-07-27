import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, describe, expect, it } from 'vitest';

import type { BuildingProfile } from './api/fetchComplexDetail';
import { BuildingProfilePanel } from './BuildingProfilePanel';

describe('건축물 profile BuildingProfilePanel', () => {
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
    expect(host.textContent).toContain('건축물대장 주건물4동');
    expect(host.textContent).toContain('평지붕');
    expect(host.textContent).toContain('테헤란로 1');
    expect(host.textContent).toContain('대지 기준');
    expect(host.textContent).toContain('확인된 동 기준');
    expect(Array.from(host.querySelectorAll('details > summary')).map((summary) => summary.textContent))
      .toEqual(['주차 상세', '안전', '에너지·인증']);
    expect(host.textContent).toContain('1등급');
    act(() => root.unmount());
  });

  it('단지 직접 면적을 우선하고 누락된 ratio만 대지 fallback badge로 표시한다', () => {
    host = document.createElement('div');
    document.body.append(host);
    const root = createRoot(host);
    const profile: BuildingProfile = {
      ratios: { scope: 'PARCEL', quality: 'PNU_FALLBACK', siteAreaM2: 20409.9,
        buildingAreaM2: 4119.66, totalFloorAreaM2: 62044.22, floorAreaRatioAreaM2: 42616.89,
        buildingCoverageRate: 20.18, floorAreaRatio: 208.8 },
      households: null, parking: null, building: null, elevators: null, safety: null,
      dates: null, address: null, energy: null,
    };
    act(() => root.render(<BuildingProfilePanel profile={profile} detail={{
      parcelId: 1, complexId: 2, latitude: null, longitude: null, address: null,
      tradeName: '단지', name: '단지', dongCnt: 5, unitCnt: 100,
      platArea: 21000, archArea: null, totArea: null, bcRat: 21, vlRat: null,
      useDate: null, prediction: null, buildingProfile: profile,
    }} />));

    expect(host.textContent).toContain('대지면적21,000㎡');
    expect(host.textContent).not.toContain('20,409.9㎡');
    expect(host.textContent).toContain('건축면적4,119.66㎡대지 기준');
    expect(host.textContent).toContain('용적률 산정 연면적42,616.89㎡대지 기준');
    expect(host.textContent).toContain('건폐율21%');
    expect(host.textContent).toContain('용적률208.8%대지 기준');
    expect(host.textContent).not.toContain('평');
    act(() => root.unmount());
  });
});
