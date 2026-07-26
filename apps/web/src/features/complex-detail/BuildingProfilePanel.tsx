import type { BuildingProfile, BuildingProfileQuality, BuildingProfileScope, BuildingProfileSeismicStatus } from './api/fetchComplexDetail';

export function BuildingProfilePanel({ profile }: { profile: BuildingProfile | null }) {
  if (profile == null) return null;
  const lifeRows = [
    row('세대·가구·호', join([
      count(profile.households?.householdCount, '세대'), count(profile.households?.familyCount, '가구'),
      count(profile.households?.unitCount, '호'),
    ]), profile.households),
    row('총 주차', count(profile.parking?.totalCount, '대'), profile.parking),
    row('세대당 주차', decimal(profile.parking?.perHousehold, '대'), profile.parking),
    row('상세 주차', join([
      parkingDetail('옥내 기계식', profile.parking?.indoorMechanicalCount, profile.parking?.indoorMechanicalAreaM2),
      parkingDetail('옥외 기계식', profile.parking?.outdoorMechanicalCount, profile.parking?.outdoorMechanicalAreaM2),
      parkingDetail('옥내 자주식', profile.parking?.indoorAutomaticCount, profile.parking?.indoorAutomaticAreaM2),
      parkingDetail('옥외 자주식', profile.parking?.outdoorAutomaticCount, profile.parking?.outdoorAutomaticAreaM2),
    ]), profile.parking),
    row('주·부속 건물', join([
      count(profile.building?.mainBuildingCount, '동', '주 건물 '),
      count(profile.building?.attachedBuildingCount, '동', '부속 건물 '),
    ]), profile.building),
    row('층수·높이', join([
      count(profile.building?.maxGroundFloorCount, '층', '지상 '),
      count(profile.building?.maxUndergroundFloorCount, '층', '지하 '),
      decimal(profile.building?.maxHeightM, 'm', '높이 '),
    ]), profile.building),
    row('승강기', join([
      count(profile.elevators?.rideUseCount, '대', '승용 '),
      count(profile.elevators?.emergencyUseCount, '대', '비상용 '),
    ]), profile.elevators),
    row('구조·지붕·용도', join([
      ...(profile.building?.structures ?? []), ...(profile.building?.roofs ?? []),
      ...(profile.building?.primaryUses ?? []),
    ]), profile.building),
    row('허가·착공', join([
      dateValue(profile.dates?.permitDate, '허가 '), dateValue(profile.dates?.constructionStartDate, '착공 '),
    ]), profile.dates),
    row('사용승인', dateValue(profile.dates?.useApprovalDate), profile.dates),
    row('도로명주소', profile.address?.roadAddress ?? null, profile.address),
  ].filter(isVisibleRow);

  const safetyEnergyRows = [
    row('내진설계', seismicLabel(profile.safety?.seismicDesignStatus), profile.safety),
    row('내진능력', join(profile.safety?.seismicAbilities ?? []), profile.safety),
    row('에너지효율', join(profile.energy?.efficiencyGrades ?? []), profile.energy),
    row('에너지 절감률', range(profile.energy?.savingRateMin, profile.energy?.savingRateMax, '%'), profile.energy),
    row('EPI', range(profile.energy?.epiMin, profile.energy?.epiMax, ''), profile.energy),
    row('친환경 인증', join([
      ...profile.energy?.greenGrades ?? [], range(profile.energy?.greenScoreMin, profile.energy?.greenScoreMax, '점'),
    ]), profile.energy),
    row('지능형 인증', join([
      ...profile.energy?.intelligentGrades ?? [],
      range(profile.energy?.intelligentScoreMin, profile.energy?.intelligentScoreMax, '점'),
    ]), profile.energy),
  ].filter(isVisibleRow);

  if (lifeRows.length === 0 && safetyEnergyRows.length === 0) return null;
  return (
    <section className="building-profile-panel" aria-label="건축물대장 생활정보">
      {lifeRows.length > 0 ? <><h3>생활정보</h3><dl>{lifeRows.map(renderRow)}</dl></> : null}
      {safetyEnergyRows.length > 0 ? (
        <details className="building-profile-disclosure">
          <summary>안전·에너지</summary>
          <dl>{safetyEnergyRows.map(renderRow)}</dl>
        </details>
      ) : null}
    </section>
  );
}

type SectionMeta = { scope: BuildingProfileScope; quality: BuildingProfileQuality } | null | undefined;
type ProfileRow = { label: string; value: string | null; badge: string | null };

function row(label: string, value: string | null, meta: SectionMeta): ProfileRow {
  return { label, value, badge: badge(meta) };
}

function isVisibleRow(value: ProfileRow): boolean { return value.value != null; }

function renderRow(value: ProfileRow) {
  return <div className="building-profile-row" key={value.label}><dt>{value.label}</dt><dd>{value.value}
    {value.badge ? <span className="building-profile-badge">{value.badge}</span> : null}</dd></div>;
}

function badge(meta: SectionMeta): string | null {
  if (meta?.quality === 'PARTIAL') return '확인된 동 기준';
  if (meta?.scope === 'PARCEL' || meta?.quality === 'PNU_FALLBACK') return '대지 기준';
  return null;
}

function count(value: number | null | undefined, suffix: string, prefix = ''): string | null {
  return value == null ? null : `${prefix}${value.toLocaleString('ko-KR')}${suffix}`;
}

function decimal(value: number | null | undefined, suffix: string, prefix = ''): string | null {
  return value == null ? null : `${prefix}${value.toLocaleString('ko-KR', { maximumFractionDigits: 2 })}${suffix}`;
}

function parkingDetail(
  label: string,
  countValue: number | null | undefined,
  areaValue: number | null | undefined,
): string | null {
  const detail = join([count(countValue, '대'), decimal(areaValue, '㎡')]);
  return detail == null ? null : `${label} ${detail}`;
}

function dateValue(value: string | null | undefined, prefix = ''): string | null {
  return value == null ? null : `${prefix}${value}`;
}

function range(min: number | null | undefined, max: number | null | undefined, suffix: string): string | null {
  if (min == null && max == null) return null;
  if (min != null && max != null && min !== max) return `${decimal(min, suffix)}–${decimal(max, suffix)}`;
  return decimal(min ?? max, suffix);
}

function join(values: Array<string | null>): string | null {
  const present = values.filter((value): value is string => value != null && value.length > 0);
  return present.length === 0 ? null : present.join(' · ');
}

function seismicLabel(value: BuildingProfileSeismicStatus | null | undefined): string | null {
  if (value === 'ALL_APPLIED') return '전체 적용';
  if (value === 'PARTIAL') return '일부 적용';
  if (value === 'NONE_APPLIED') return '미적용';
  if (value === 'UNKNOWN') return '확인 불가';
  return null;
}
