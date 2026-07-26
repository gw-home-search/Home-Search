import { resolveApiUrl } from '../../map/api/resolveApiUrl';
import { readValidatedJson, requestFailureFromResponse } from '../../../shared/http/requestFailure';
import { fetchWithTimeout } from '../../../shared/http/fetchWithTimeout';

export type ComplexDetail = {
  parcelId: number;
  complexId: number | null;
  latitude: number | null;
  longitude: number | null;
  address: string | null;
  displayName?: string | null;
  tradeName: string | null;
  name: string;
  dongCnt: number | null;
  unitCnt: number | null;
  platArea: number | null;
  archArea: number | null;
  totArea: number | null;
  bcRat: number | null;
  vlRat: number | null;
  useDate: string | null;
  prediction: PricePrediction | null;
  buildingProfile: BuildingProfile | null;
};

export type BuildingProfileScope = 'COMPLEX' | 'PARCEL';
export type BuildingProfileQuality = 'VERIFIED' | 'PNU_FALLBACK' | 'PARTIAL';
export type BuildingProfileSeismicStatus = 'ALL_APPLIED' | 'PARTIAL' | 'NONE_APPLIED' | 'UNKNOWN';
type ProfileSection = { scope: BuildingProfileScope; quality: BuildingProfileQuality };
export type BuildingProfile = {
  ratios: (ProfileSection & { buildingCoverageRate: number | null; floorAreaRatio: number | null; siteAreaM2: number | null; buildingAreaM2: number | null; totalFloorAreaM2: number | null; floorAreaRatioAreaM2: number | null }) | null;
  households: (ProfileSection & { householdCount: number | null; familyCount: number | null; unitCount: number | null }) | null;
  parking: (ProfileSection & { totalCount: number | null; perHousehold: number | null; indoorMechanicalCount: number | null; indoorMechanicalAreaM2: number | null; outdoorMechanicalCount: number | null; outdoorMechanicalAreaM2: number | null; indoorAutomaticCount: number | null; indoorAutomaticAreaM2: number | null; outdoorAutomaticCount: number | null; outdoorAutomaticAreaM2: number | null }) | null;
  building: (ProfileSection & { mainBuildingCount: number | null; attachedBuildingCount: number | null; maxGroundFloorCount: number | null; maxUndergroundFloorCount: number | null; maxHeightM: number | null; structures: string[]; roofs: string[]; primaryUses: string[] }) | null;
  elevators: (ProfileSection & { rideUseCount: number | null; emergencyUseCount: number | null }) | null;
  safety: (ProfileSection & { seismicDesignStatus: BuildingProfileSeismicStatus | null; seismicAbilities: string[] }) | null;
  dates: (ProfileSection & { permitDate: string | null; constructionStartDate: string | null; useApprovalDate: string | null }) | null;
  address: (ProfileSection & { parcelAddress: string | null; roadAddress: string | null }) | null;
  energy: (ProfileSection & { efficiencyGrades: string[]; savingRateMin: number | null; savingRateMax: number | null; epiMin: number | null; epiMax: number | null; greenGrades: string[]; greenScoreMin: number | null; greenScoreMax: number | null; intelligentGrades: string[]; intelligentScoreMin: number | null; intelligentScoreMax: number | null }) | null;
};

export type PricePredictionStatus = 'PENDING' | 'READY' | 'FAILED' | 'UNAVAILABLE';

export type PricePrediction = {
  status: PricePredictionStatus;
  modelVersion: string | null;
  predictedDealAmount: number | null;
  predictedPricePerM2: number | null;
  predictedPricePerPyeong: number | null;
  intervalLow: number | null;
  intervalHigh: number | null;
  intervalBasis: string | null;
  targetAreaM2: number | null;
  targetFloor: number | null;
  basisTradeId: number | null;
  basisDealDate: string | null;
  generatedAt: string | null;
};

type ComplexDetailResponse = {
  parcelId?: number | string;
  complexId?: number | string | null;
  latitude?: number | string | null;
  longitude?: number | string | null;
  address?: string | null;
  displayName?: string | null;
  tradeName?: string | null;
  name?: string | null;
  dongCnt?: number | string | null;
  unitCnt?: number | string | null;
  platArea?: number | string | null;
  archArea?: number | string | null;
  totArea?: number | string | null;
  bcRat?: number | string | null;
  vlRat?: number | string | null;
  useDate?: string | null;
  prediction?: PricePredictionResponse | null;
  buildingProfile?: unknown;
};

type PricePredictionResponse = {
  status?: string | null;
  modelVersion?: string | null;
  predictedDealAmount?: number | string | null;
  predictedPricePerM2?: number | string | null;
  predictedPricePerPyeong?: number | string | null;
  intervalLow?: number | string | null;
  intervalHigh?: number | string | null;
  intervalBasis?: string | null;
  targetAreaM2?: number | string | null;
  targetFloor?: number | string | null;
  basisTradeId?: number | string | null;
  basisDealDate?: string | null;
  generatedAt?: string | null;
};

const DETAIL_PATH = '/api/v1/detail';
const COMPLEX_PATH = '/api/v1/complex';

export async function fetchComplexDetail(
  parcelId: number,
  complexId?: number | null,
  signal?: AbortSignal,
): Promise<ComplexDetail> {
  const response = await fetchWithTimeout(resolveApiUrl(scopedPath(`${DETAIL_PATH}/${parcelId}`, complexId)), {
    method: 'GET',
    signal,
  });

  if (!response.ok) {
    throw await requestFailureFromResponse(response, {
      service: 'property-data',
      operation: 'complex-detail',
    });
  }

  return readValidatedJson(response, {
    service: 'property-data',
    operation: 'complex-detail',
  }, normalizeComplexDetailPayload);
}

export async function fetchComplexDetailByComplexId(
  complexId: number,
  signal?: AbortSignal,
): Promise<ComplexDetail> {
  const response = await fetchWithTimeout(resolveApiUrl(`${COMPLEX_PATH}/${complexId}`), {
    method: 'GET', signal,
  });

  if (!response.ok) {
    throw await requestFailureFromResponse(response, {
      service: 'property-data',
      operation: 'complex-detail',
    });
  }

  return readValidatedJson(response, {
    service: 'property-data',
    operation: 'complex-detail',
  }, normalizeComplexDetailPayload);
}

function normalizeComplexDetailPayload(payload: unknown): ComplexDetail {
  if (!isRecord(payload)) {
    throw new Error('Invalid public API complex detail response: expected an object');
  }
  return normalizeComplexDetail(payload);
}

function normalizeComplexDetail(detail: ComplexDetailResponse): ComplexDetail {
  return {
    parcelId: toRequiredNumber(detail.parcelId, 'parcelId'),
    complexId: toNullableNumber(detail.complexId, 'complexId'),
    latitude: toNullableNumber(detail.latitude, 'latitude'),
    longitude: toNullableNumber(detail.longitude, 'longitude'),
    address: toNullableString(detail.address),
    displayName: toNullableString(detail.displayName),
    tradeName: toNullableString(detail.tradeName),
    name: toRequiredString(detail.name, 'name'),
    dongCnt: toNullableNumber(detail.dongCnt, 'dongCnt'),
    unitCnt: toNullableNumber(detail.unitCnt, 'unitCnt'),
    platArea: toNullableNumber(detail.platArea, 'platArea'),
    archArea: toNullableNumber(detail.archArea, 'archArea'),
    totArea: toNullableNumber(detail.totArea, 'totArea'),
    bcRat: toNullableNumber(detail.bcRat, 'bcRat'),
    vlRat: toNullableNumber(detail.vlRat, 'vlRat'),
    useDate: toNullableString(detail.useDate),
    prediction: normalizePrediction(detail.prediction),
    buildingProfile: normalizeBuildingProfile(detail.buildingProfile),
  };
}

function normalizeBuildingProfile(value: unknown): BuildingProfile | null {
  if (value == null) return null;
  if (!isObjectRecord(value)) throw new Error('Invalid public API complex detail response: buildingProfile must be an object');
  const ratios = profileSection(value.ratios, (section, meta) => ({ ...meta,
    buildingCoverageRate: positiveNumber(section.buildingCoverageRate), floorAreaRatio: positiveNumber(section.floorAreaRatio),
    siteAreaM2: positiveNumber(section.siteAreaM2), buildingAreaM2: positiveNumber(section.buildingAreaM2),
    totalFloorAreaM2: positiveNumber(section.totalFloorAreaM2), floorAreaRatioAreaM2: positiveNumber(section.floorAreaRatioAreaM2),
  }));
  const households = profileSection(value.households, (section, meta) => ({ ...meta,
    householdCount: nullableNumber(section.householdCount), familyCount: nullableNumber(section.familyCount),
    unitCount: nullableNumber(section.unitCount),
  }));
  const parking = profileSection(value.parking, (section, meta) => ({ ...meta,
    totalCount: nullableNumber(section.totalCount), perHousehold: positiveNumber(section.perHousehold),
    indoorMechanicalCount: nullableNumber(section.indoorMechanicalCount), indoorMechanicalAreaM2: positiveNumber(section.indoorMechanicalAreaM2),
    outdoorMechanicalCount: nullableNumber(section.outdoorMechanicalCount), outdoorMechanicalAreaM2: positiveNumber(section.outdoorMechanicalAreaM2),
    indoorAutomaticCount: nullableNumber(section.indoorAutomaticCount), indoorAutomaticAreaM2: positiveNumber(section.indoorAutomaticAreaM2),
    outdoorAutomaticCount: nullableNumber(section.outdoorAutomaticCount), outdoorAutomaticAreaM2: positiveNumber(section.outdoorAutomaticAreaM2),
  }));
  const building = profileSection(value.building, (section, meta) => ({ ...meta,
    mainBuildingCount: nullableNumber(section.mainBuildingCount), attachedBuildingCount: nullableNumber(section.attachedBuildingCount),
    maxGroundFloorCount: nullableNumber(section.maxGroundFloorCount), maxUndergroundFloorCount: nullableNumber(section.maxUndergroundFloorCount),
    maxHeightM: positiveNumber(section.maxHeightM), structures: stringList(section.structures), roofs: stringList(section.roofs),
    primaryUses: stringList(section.primaryUses),
  }));
  const elevators = profileSection(value.elevators, (section, meta) => ({ ...meta,
    rideUseCount: nullableNumber(section.rideUseCount), emergencyUseCount: nullableNumber(section.emergencyUseCount),
  }));
  const safety = profileSection(value.safety, (section, meta) => ({ ...meta,
    seismicDesignStatus: seismicStatus(section.seismicDesignStatus), seismicAbilities: stringList(section.seismicAbilities),
  }));
  const dates = profileSection(value.dates, (section, meta) => ({ ...meta,
    permitDate: nullableString(section.permitDate), constructionStartDate: nullableString(section.constructionStartDate),
    useApprovalDate: nullableString(section.useApprovalDate),
  }));
  const address = profileSection(value.address, (section, meta) => ({ ...meta,
    parcelAddress: nullableString(section.parcelAddress), roadAddress: nullableString(section.roadAddress),
  }));
  const energy = profileSection(value.energy, (section, meta) => ({ ...meta,
    efficiencyGrades: stringList(section.efficiencyGrades), savingRateMin: positiveNumber(section.savingRateMin),
    savingRateMax: positiveNumber(section.savingRateMax), epiMin: positiveNumber(section.epiMin), epiMax: positiveNumber(section.epiMax),
    greenGrades: stringList(section.greenGrades), greenScoreMin: positiveNumber(section.greenScoreMin),
    greenScoreMax: positiveNumber(section.greenScoreMax), intelligentGrades: stringList(section.intelligentGrades),
    intelligentScoreMin: positiveNumber(section.intelligentScoreMin), intelligentScoreMax: positiveNumber(section.intelligentScoreMax),
  }));
  return { ratios, households, parking, building, elevators, safety, dates, address, energy };
}

function profileSection<T>(
  value: unknown,
  map: (section: Record<string, unknown>, meta: ProfileSection) => T,
): T | null {
  if (value == null) return null;
  if (!isObjectRecord(value)) throw new Error('Invalid public API complex detail response: profile section must be an object');
  return map(value, { scope: profileScope(value.scope), quality: profileQuality(value.quality) });
}

function profileScope(value: unknown): BuildingProfileScope {
  if (value === 'COMPLEX' || value === 'PARCEL') return value;
  throw new Error('Invalid public API complex detail response: building profile scope is invalid');
}

function profileQuality(value: unknown): BuildingProfileQuality {
  if (value === 'VERIFIED' || value === 'PNU_FALLBACK' || value === 'PARTIAL') return value;
  throw new Error('Invalid public API complex detail response: building profile quality is invalid');
}

function nullableNumber(value: unknown): number | null {
  return toNullableNumber(value, 'buildingProfile');
}

function positiveNumber(value: unknown): number | null {
  const number = nullableNumber(value);
  return number != null && number > 0 ? number : null;
}

function nullableString(value: unknown): string | null {
  return toNullableString(value);
}

function stringList(value: unknown): string[] {
  if (value == null) return [];
  if (!Array.isArray(value)) throw new Error('Invalid public API complex detail response: profile set must be an array');
  return value.flatMap((item) => typeof item === 'string' && item.trim() ? [item.trim()] : []);
}

function seismicStatus(value: unknown): BuildingProfileSeismicStatus | null {
  if (value == null) return null;
  if (value === 'ALL_APPLIED' || value === 'PARTIAL' || value === 'NONE_APPLIED' || value === 'UNKNOWN') return value;
  throw new Error('Invalid public API complex detail response: seismic status is invalid');
}

function normalizePrediction(prediction: unknown): PricePrediction | null {
  if (prediction == null) {
    return null;
  }

  if (!isObjectRecord(prediction)) {
    throw new Error('Invalid public API complex detail response: prediction must be an object');
  }

  return {
    status: toPredictionStatus(prediction.status),
    modelVersion: toNullableString(prediction.modelVersion),
    predictedDealAmount: toNullableNumber(prediction.predictedDealAmount, 'prediction.predictedDealAmount'),
    predictedPricePerM2: toNullableNumber(prediction.predictedPricePerM2, 'prediction.predictedPricePerM2'),
    predictedPricePerPyeong: toNullableNumber(
      prediction.predictedPricePerPyeong,
      'prediction.predictedPricePerPyeong',
    ),
    intervalLow: toNullableNumber(prediction.intervalLow, 'prediction.intervalLow'),
    intervalHigh: toNullableNumber(prediction.intervalHigh, 'prediction.intervalHigh'),
    intervalBasis: toNullableString(prediction.intervalBasis),
    targetAreaM2: toNullableNumber(prediction.targetAreaM2, 'prediction.targetAreaM2'),
    targetFloor: toNullableNumber(prediction.targetFloor, 'prediction.targetFloor'),
    basisTradeId: toNullableNumber(prediction.basisTradeId, 'prediction.basisTradeId'),
    basisDealDate: toNullableString(prediction.basisDealDate),
    generatedAt: toNullableString(prediction.generatedAt),
  };
}

function scopedPath(path: string, complexId?: number | null): string {
  return complexId == null ? path : `${path}?complexId=${encodeURIComponent(complexId)}`;
}

function isRecord(value: unknown): value is ComplexDetailResponse {
  return isObjectRecord(value);
}

function isObjectRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function toPredictionStatus(value: unknown): PricePredictionStatus {
  if (value === 'PENDING' || value === 'READY' || value === 'FAILED' || value === 'UNAVAILABLE') {
    return value;
  }

  throw new Error('Invalid public API complex detail response: prediction.status is invalid');
}

function toRequiredNumber(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new Error(`Invalid public API complex detail response: ${field} must be a number`);
  }

  return value;
}

function toNullableNumber(value: unknown, field: string): number | null {
  if (value == null) {
    return null;
  }

  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new Error(`Invalid public API complex detail response: ${field} must be a number`);
  }

  return value;
}

function toRequiredString(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.length === 0) {
    throw new Error(`Invalid public API complex detail response: ${field} must be a non-empty string`);
  }

  return value;
}

function toNullableString(value: unknown): string | null {
  if (value == null) {
    return null;
  }

  if (typeof value !== 'string') {
    return null;
  }

  return value.length > 0 ? value : null;
}
