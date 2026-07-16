import { describe, expect, it } from 'vitest';

import {
  createComplexMarkerViewModel,
  createRegionMarkerViewModel,
  declutterComplexMarkers,
  regionMarkerDensityForLevel,
} from './markerViewModel';

describe('markerViewModel 지도 마커 모델', () => {
  it('complex marker anatomy와 selected 상태를 색 외의 신호로 고정한다', () => {
    const viewModel = createComplexMarkerViewModel({
      parcelId: 1001,
      complexId: 501,
      name: '선택 단지',
      lat: 37.5,
      lng: 127,
      latestDealAmount: 125000,
      unitCntSum: 740,
    }, true);

    expect(viewModel).toMatchObject({
      kind: 'complex',
      shape: 'price-card',
      key: '1001-501',
      kicker: '최근 실거래',
      price: '12.5억',
      meta: '선택 단지 · 740세대',
      symbol: 'price-card',
      selected: true,
      state: 'selected',
    });
    expect(viewModel.ariaLabel).toBe('필지 1001 단지 501 상세 열기');
  });

  it('complex marker 보조 문구는 이름과 세대수의 독립 fallback을 유지한다', () => {
    const base = { parcelId: 1, complexId: 2, lat: 37.5, lng: 127, latestDealAmount: null };
    expect(createComplexMarkerViewModel({ ...base, name: '이름만', unitCntSum: null }, false).meta)
      .toBe('이름만');
    expect(createComplexMarkerViewModel({ ...base, name: null, unitCntSum: 320 }, false).meta)
      .toBe('320세대');
    expect(createComplexMarkerViewModel({ ...base, name: null, unitCntSum: null }, false).meta)
      .toBeNull();
  });

  it('region marker는 별도 capsule anatomy와 세대수 label을 제공한다', () => {
    expect(createRegionMarkerViewModel({
      id: 1,
      name: '서울',
      lat: 37.5,
      lng: 127,
      unitCntSum: 1200,
    })).toMatchObject({
      kind: 'region',
      shape: 'split-card',
      key: '1',
      name: '서울',
      meta: '1,200세대',
      ariaLabel: '지역 이동 서울',
    });
  });

  it('지도 level에 따라 region marker density를 단계적으로 축소한다', () => {
    expect(regionMarkerDensityForLevel(6)).toBe('dense');
    expect(regionMarkerDensityForLevel(8)).toBe('compact');
    expect(regionMarkerDensityForLevel(10)).toBe('standard');
    expect(regionMarkerDensityForLevel(11)).toBe('overview');
    expect(regionMarkerDensityForLevel(12)).toBe('overview');
  });

  it('근접 complex marker는 선택 단지를 우선 노출하고 숨겨진 개수를 반환한다', () => {
    const markers = [
      { parcelId: 1, complexId: 11, name: 'A', lat: 37.5, lng: 127, latestDealAmount: 10000, unitCntSum: 100 },
      { parcelId: 2, complexId: 22, name: 'B', lat: 37.50005, lng: 127.00005, latestDealAmount: 20000, unitCntSum: 200 },
      { parcelId: 3, complexId: 33, name: 'C', lat: 37.51, lng: 127.01, latestDealAmount: 30000, unitCntSum: 300 },
    ];
    expect(declutterComplexMarkers(markers, { parcelId: 2, complexId: 22 }, 4)).toEqual({
      markers: [markers[1], markers[2]],
      hiddenCount: 1,
    });
  });
});
