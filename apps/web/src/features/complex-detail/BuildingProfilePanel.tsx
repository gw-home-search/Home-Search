import type {
  BuildingProfile,
  BuildingProfileQuality,
  BuildingProfileScope,
  BuildingProfileSeismicStatus,
  ComplexDetail,
} from './api/fetchComplexDetail';
import { formatDecimal, formatLargeArea } from './formatDetailValue';

export function BuildingProfilePanel({
  profile,
  detail = null,
}: {
  profile: BuildingProfile | null;
  detail?: ComplexDetail | null;
}) {
  const ratioRows = buildRatioRows(profile, detail);
  const lifeRows = buildLifeRows(profile, detail);
  const parkingRows = buildParkingRows(profile);
  const safetyRows = buildSafetyRows(profile);
  const energyRows = buildEnergyRows(profile);

  if (
    ratioRows.length === 0
    && lifeRows.length === 0
    && parkingRows.length === 0
    && safetyRows.length === 0
    && energyRows.length === 0
  ) return null;

  return (
    <section className="building-profile-panel" aria-label="단지 면적과 건축물대장 정보" data-detail-order="profile">
      {ratioRows.length > 0 ? (
        <section className="building-profile-section" aria-labelledby="building-profile-ratios">
          <h3 id="building-profile-ratios">면적·밀도</h3>
          <dl>{ratioRows.map(renderRow)}</dl>
        </section>
      ) : null}
      {lifeRows.length > 0 || parkingRows.length > 0 ? (
        <section className="building-profile-section" aria-labelledby="building-profile-life">
          <h3 id="building-profile-life">생활·건축</h3>
          {lifeRows.length > 0 ? <dl>{lifeRows.map(renderRow)}</dl> : null}
          {parkingRows.length > 0 ? (
            <details className="building-profile-disclosure">
              <summary>주차 상세</summary>
              <dl>{parkingRows.map(renderRow)}</dl>
            </details>
          ) : null}
        </section>
      ) : null}
      {safetyRows.length > 0 ? (
        <details className="building-profile-disclosure">
          <summary>안전</summary>
          <dl>{safetyRows.map(renderRow)}</dl>
        </details>
      ) : null}
      {energyRows.length > 0 ? (
        <details className="building-profile-disclosure">
          <summary>에너지·인증</summary>
          <dl>{energyRows.map(renderRow)}</dl>
        </details>
      ) : null}
    </section>
  );
}

type SectionMeta = { scope: BuildingProfileScope; quality: BuildingProfileQuality } | null | undefined;
type ProfileRow = { label: string; value: string | null; badge: string | null };

function buildRatioRows(profile: BuildingProfile | null, detail: ComplexDetail | null): ProfileRow[] {
  const ratios = profile?.ratios;
  return [
    directFirstRow('대지면적', detail?.platArea, ratios?.siteAreaM2, formatLargeArea, ratios),
    directFirstRow('건축면적', detail?.archArea, ratios?.buildingAreaM2, formatLargeArea, ratios),
    directFirstRow('연면적', detail?.totArea, ratios?.totalFloorAreaM2, formatLargeArea, ratios),
    row('용적률 산정 연면적', nullableFormat(ratios?.floorAreaRatioAreaM2, formatLargeArea), ratios),
    directFirstRow('건폐율', detail?.bcRat, ratios?.buildingCoverageRate, (value) => formatDecimal(value, '%'), ratios),
    directFirstRow('용적률', detail?.vlRat, ratios?.floorAreaRatio, (value) => formatDecimal(value, '%'), ratios),
  ].filter(isVisibleRow);
}

function buildLifeRows(profile: BuildingProfile | null, detail: ComplexDetail | null): ProfileRow[] {
  if (profile == null) return [];
  const householdCount = profile.households?.householdCount;
  const useApprovalDate = profile.dates?.useApprovalDate;
  return [
    householdCount != null && householdCount !== detail?.unitCnt
      ? row('건축물대장 세대수', count(householdCount, '세대'), profile.households)
      : row('', null, null),
    row('건축물대장 가구·호', join([
      count(profile.households?.familyCount, '가구'),
      count(profile.households?.unitCount, '호'),
    ]), profile.households),
    row('총 주차', count(profile.parking?.totalCount, '대'), profile.parking),
    row('세대당 주차', decimal(profile.parking?.perHousehold, '대'), profile.parking),
    row('승강기', join([
      count(profile.elevators?.rideUseCount, '대', '승용 '),
      count(profile.elevators?.emergencyUseCount, '대', '비상용 '),
    ]), profile.elevators),
    row('건축물대장 주건물', count(profile.building?.mainBuildingCount, '동'), profile.building),
    row('건축물대장 부속건물', count(profile.building?.attachedBuildingCount, '동'), profile.building),
    row('최고 지상층', count(profile.building?.maxGroundFloorCount, '층'), profile.building),
    row('최고 지하층', count(profile.building?.maxUndergroundFloorCount, '층'), profile.building),
    row('최고 높이', decimal(profile.building?.maxHeightM, 'm'), profile.building),
    row('구조', join(profile.building?.structures ?? []), profile.building),
    row('지붕', join(profile.building?.roofs ?? []), profile.building),
    row('주요 용도', join(profile.building?.primaryUses ?? []), profile.building),
    row('허가일', dateValue(profile.dates?.permitDate), profile.dates),
    row('착공일', dateValue(profile.dates?.constructionStartDate), profile.dates),
    useApprovalDate != null && useApprovalDate !== detail?.useDate
      ? row('건축물대장 사용승인일', useApprovalDate, profile.dates)
      : row('', null, null),
    row('도로명주소', profile.address?.roadAddress ?? null, profile.address),
  ].filter(isVisibleRow);
}

function buildParkingRows(profile: BuildingProfile | null): ProfileRow[] {
  if (profile?.parking == null) return [];
  return [
    parkingRow('옥내 기계식', profile.parking.indoorMechanicalCount, profile.parking.indoorMechanicalAreaM2, profile.parking),
    parkingRow('옥외 기계식', profile.parking.outdoorMechanicalCount, profile.parking.outdoorMechanicalAreaM2, profile.parking),
    parkingRow('옥내 자주식', profile.parking.indoorAutomaticCount, profile.parking.indoorAutomaticAreaM2, profile.parking),
    parkingRow('옥외 자주식', profile.parking.outdoorAutomaticCount, profile.parking.outdoorAutomaticAreaM2, profile.parking),
  ].filter(isVisibleRow);
}

function buildSafetyRows(profile: BuildingProfile | null): ProfileRow[] {
  return [
    row('내진설계', seismicLabel(profile?.safety?.seismicDesignStatus), profile?.safety),
    row('내진능력', join(profile?.safety?.seismicAbilities ?? []), profile?.safety),
  ].filter(isVisibleRow);
}

function buildEnergyRows(profile: BuildingProfile | null): ProfileRow[] {
  return [
    row('효율등급', join(profile?.energy?.efficiencyGrades ?? []), profile?.energy),
    row('에너지 절감률', range(profile?.energy?.savingRateMin, profile?.energy?.savingRateMax, '%'), profile?.energy),
    row('EPI', range(profile?.energy?.epiMin, profile?.energy?.epiMax, ''), profile?.energy),
    row('친환경 인증', join([
      ...profile?.energy?.greenGrades ?? [],
      range(profile?.energy?.greenScoreMin, profile?.energy?.greenScoreMax, '점'),
    ]), profile?.energy),
    row('지능형 인증', join([
      ...profile?.energy?.intelligentGrades ?? [],
      range(profile?.energy?.intelligentScoreMin, profile?.energy?.intelligentScoreMax, '점'),
    ]), profile?.energy),
  ].filter(isVisibleRow);
}

function directFirstRow(
  label: string,
  directValue: number | null | undefined,
  fallbackValue: number | null | undefined,
  format: (value: number) => string,
  fallbackMeta: SectionMeta,
): ProfileRow {
  if (directValue != null) return row(label, format(directValue), null);
  return row(label, nullableFormat(fallbackValue, format), fallbackMeta);
}

function nullableFormat(
  value: number | null | undefined,
  format: (value: number) => string,
): string | null {
  return value == null ? null : format(value);
}

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

function parkingRow(label: string, countValue: number | null, areaValue: number | null, meta: SectionMeta) {
  return row(label, join([count(countValue, '대'), nullableFormat(areaValue, formatLargeArea)]), meta);
}

function count(value: number | null | undefined, suffix: string, prefix = ''): string | null {
  return value == null ? null : `${prefix}${value.toLocaleString('ko-KR')}${suffix}`;
}

function decimal(value: number | null | undefined, suffix: string, prefix = ''): string | null {
  return value == null ? null : `${prefix}${formatDecimal(value, suffix)}`;
}

function dateValue(value: string | null | undefined): string | null {
  return value ?? null;
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
