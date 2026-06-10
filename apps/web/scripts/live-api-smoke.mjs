const DEFAULT_API_BASE_URL = 'http://127.0.0.1:8080';
const DEFAULT_QUERY = '힐스테이트세운센트럴';
const SEOUL_BOUNDS = {
  swLat: 37.45,
  swLng: 126.85,
  neLat: 37.7,
  neLng: 127.2,
};

const apiBaseUrl = normalizeBaseUrl(
  process.env.HOME_SEARCH_API_BASE_URL ?? process.env.VITE_API_BASE_URL ?? DEFAULT_API_BASE_URL,
);
const liveQuery = process.env.HOME_SEARCH_LIVE_QUERY ?? DEFAULT_QUERY;
const requestTimeoutMs = Number.parseInt(process.env.HOME_SEARCH_LIVE_TIMEOUT_MS ?? '10000', 10);
const hits = [];

async function main() {
  const suggestions = await fetchJson(`/api/v1/search/complexes/suggestions?${query({ q: liveQuery })}`);
  assertArray(suggestions, 'GET /api/v1/search/complexes/suggestions');
  assertNonEmpty(suggestions, `GET /api/v1/search/complexes/suggestions?q=${liveQuery}`);
  for (const suggestion of suggestions.slice(0, 3)) {
    assertComplexSearchSummary(suggestion, 'suggestion');
  }

  const searchResults = await fetchJson(`/api/v1/search/complexes?${query({ q: liveQuery })}`);
  assertArray(searchResults, 'GET /api/v1/search/complexes');
  assertNonEmpty(searchResults, `GET /api/v1/search/complexes?q=${liveQuery}`);
  for (const result of searchResults.slice(0, 3)) {
    assertComplexSearchSummary(result, 'search result');
  }

  const selected = chooseComplex(searchResults, suggestions);
  const parcelId = toRequiredNumber(selected.parcelId, 'selected.parcelId');
  const complexId = toRequiredNumber(selected.complexId, 'selected.complexId');
  const markerBounds = boundsAroundSearchResult(selected);

  const complexMarkers = await fetchJson('/api/v1/map/complexes', {
    method: 'POST',
    body: {
      ...markerBounds,
      pyeongMin: null,
      pyeongMax: null,
      priceEokMin: null,
      priceEokMax: null,
      ageMin: null,
      ageMax: null,
      unitMin: null,
      unitMax: null,
    },
  });
  assertArray(complexMarkers, 'POST /api/v1/map/complexes');
  assertNonEmpty(complexMarkers, 'POST /api/v1/map/complexes');
  for (const marker of complexMarkers.slice(0, 3)) {
    assertComplexMarker(marker);
  }

  const regionMarkers = await fetchJson('/api/v1/map/regions', {
    method: 'POST',
    body: {
      ...SEOUL_BOUNDS,
      region: 'si-gun-gu',
    },
  });
  assertArray(regionMarkers, 'POST /api/v1/map/regions');
  assertNonEmpty(regionMarkers, 'POST /api/v1/map/regions');
  for (const marker of regionMarkers.slice(0, 3)) {
    assertRegionMarker(marker);
  }

  const detail = await fetchJson(`/api/v1/detail/${parcelId}?${query({ complexId })}`);
  assertComplexDetail(detail, { parcelId, complexId });

  const trades = await fetchJson(`/api/v1/trade/${parcelId}?${query({ complexId })}`);
  assertTradeEnvelope(trades, { parcelId, complexId });

  const parcelComplexes = await fetchJson(`/api/v1/detail/${parcelId}/complexes`);
  assertArray(parcelComplexes, `GET /api/v1/detail/${parcelId}/complexes`);
  assertNonEmpty(parcelComplexes, `GET /api/v1/detail/${parcelId}/complexes`);
  for (const complex of parcelComplexes.slice(0, 3)) {
    assertParcelComplexSummary(complex);
  }

  const directDetail = await fetchJson(`/api/v1/complex/${complexId}`);
  assertComplexDetail(directDetail, { complexId });

  const directTrades = await fetchJson(`/api/v1/complex/${complexId}/trades`);
  assertTradeEnvelope(directTrades, { complexId });

  const rootRegions = await fetchJson('/api/v1/region');
  assertArray(rootRegions, 'GET /api/v1/region');
  assertNonEmpty(rootRegions, 'GET /api/v1/region');
  for (const region of rootRegions.slice(0, 3)) {
    assertRegionSummary(region);
  }

  const regionProbe = await findRegionProbe(rootRegions);
  assertRegionDetail(regionProbe.detail, regionProbe.regionId);
  assertArray(regionProbe.complexes, `GET /api/v1/region/${regionProbe.regionId}/complexes`);
  assertNonEmpty(regionProbe.complexes, `GET /api/v1/region/${regionProbe.regionId}/complexes`);
  for (const complex of regionProbe.complexes.slice(0, 3)) {
    assertParcelComplexSummary(complex);
  }

  assertRequiredHits([
    '/api/v1/map/complexes',
    '/api/v1/map/regions',
    '/api/v1/search/complexes/suggestions',
    '/api/v1/search/complexes',
    `/api/v1/detail/${parcelId}`,
    `/api/v1/trade/${parcelId}`,
    `/api/v1/detail/${parcelId}/complexes`,
    `/api/v1/complex/${complexId}`,
    `/api/v1/complex/${complexId}/trades`,
    '/api/v1/region',
    `/api/v1/region/${regionProbe.regionId}`,
    `/api/v1/region/${regionProbe.regionId}/complexes`,
  ]);

  console.log([
    '실데이터 public API smoke: pass',
    `apiBaseUrl=${apiBaseUrl}`,
    `query=${liveQuery}`,
    `selectedComplex=${selected.complexName} complexId=${complexId} parcelId=${parcelId}`,
    `mapComplexMarkers=${complexMarkers.length}`,
    `mapRegionMarkers=${regionMarkers.length}`,
    `trades=${trades.trades.length}`,
    `parcelComplexes=${parcelComplexes.length}`,
    `region=${regionProbe.detail.name} regionComplexes=${regionProbe.complexes.length}`,
    `hitCount=${hits.length}`,
  ].join('\n'));
}

async function findRegionProbe(rootRegions) {
  const queue = [...rootRegions.map((region) => ({
    id: toRequiredNumber(region.id, 'region.id'),
    depth: 0,
  }))];
  let fallback = null;

  while (queue.length > 0) {
    const current = queue.shift();
    if (!current || current.depth > 2) {
      continue;
    }

    const detail = await fetchJson(`/api/v1/region/${current.id}`);
    assertRegionDetail(detail, current.id);

    const complexes = await fetchJson(`/api/v1/region/${current.id}/complexes?${query({
      limit: 20,
      offset: 0,
    })}`);
    assertArray(complexes, `GET /api/v1/region/${current.id}/complexes`);

    fallback ??= { regionId: current.id, detail, complexes };
    if (complexes.length > 0) {
      return { regionId: current.id, detail, complexes };
    }

    for (const child of detail.children) {
      queue.push({
        id: toRequiredNumber(child.id, 'region.child.id'),
        depth: current.depth + 1,
      });
    }
  }

  if (fallback != null) {
    return fallback;
  }

  throw new Error('실데이터 region probe 실패: 조회 가능한 region detail이 없습니다.');
}

async function fetchJson(path, options = {}) {
  const url = `${apiBaseUrl}${path}`;
  const response = await fetch(url, {
    method: options.method ?? 'GET',
    headers: options.body == null ? undefined : { 'Content-Type': 'application/json' },
    body: options.body == null ? undefined : JSON.stringify(options.body),
    signal: AbortSignal.timeout(requestTimeoutMs),
  });
  hits.push({ method: options.method ?? 'GET', path, status: response.status });

  if (!response.ok) {
    const body = await response.text();
    throw new Error(`${options.method ?? 'GET'} ${path} failed: ${response.status} ${body}`);
  }

  return response.json();
}

function chooseComplex(searchResults, suggestions) {
  const selected = searchResults.find((result) => result.complexId != null && result.parcelId != null)
    ?? suggestions.find((suggestion) => suggestion.complexId != null && suggestion.parcelId != null);
  if (!isRecord(selected)) {
    throw new Error('실데이터 search 결과에서 parcelId/complexId를 가진 단지를 찾지 못했습니다.');
  }
  return selected;
}

function boundsAroundSearchResult(result) {
  const latitude = toNullableNumber(result.latitude, 'selected.latitude');
  const longitude = toNullableNumber(result.longitude, 'selected.longitude');
  if (latitude == null || longitude == null) {
    return SEOUL_BOUNDS;
  }

  const delta = 0.01;
  return {
    swLat: latitude - delta,
    swLng: longitude - delta,
    neLat: latitude + delta,
    neLng: longitude + delta,
  };
}

function assertComplexMarker(marker) {
  assertRecord(marker, 'complex marker');
  toRequiredNumber(marker.parcelId ?? marker.id, 'complex marker parcelId');
  toNullableNumber(marker.complexId, 'complex marker complexId');
  toRequiredNumber(marker.lat ?? marker.latitude, 'complex marker lat');
  toRequiredNumber(marker.lng ?? marker.longitude, 'complex marker lng');
  toNullableNumber(marker.latestDealAmount, 'complex marker latestDealAmount');
  toRequiredNumber(marker.unitCntSum, 'complex marker unitCntSum');
}

function assertRegionMarker(marker) {
  assertRecord(marker, 'region marker');
  toRequiredNumber(marker.id, 'region marker id');
  toRequiredString(marker.name ?? marker.regionName, 'region marker name');
  toRequiredNumber(marker.lat ?? marker.latitude, 'region marker lat');
  toRequiredNumber(marker.lng ?? marker.longitude, 'region marker lng');
}

function assertComplexSearchSummary(summary, sourceName) {
  assertRecord(summary, sourceName);
  toRequiredNumber(summary.complexId, `${sourceName}.complexId`);
  toRequiredString(summary.complexName, `${sourceName}.complexName`);
  toRequiredNumber(summary.parcelId, `${sourceName}.parcelId`);
  toNullableNumber(summary.latitude, `${sourceName}.latitude`);
  toNullableNumber(summary.longitude, `${sourceName}.longitude`);
  toNullableString(summary.address, `${sourceName}.address`);
}

function assertComplexDetail(detail, expected) {
  assertRecord(detail, 'complex detail');
  if (expected.parcelId != null && toRequiredNumber(detail.parcelId, 'detail.parcelId') !== expected.parcelId) {
    throw new Error(`detail.parcelId mismatch: expected ${expected.parcelId}, got ${detail.parcelId}`);
  }
  if (expected.complexId != null && toRequiredNumber(detail.complexId, 'detail.complexId') !== expected.complexId) {
    throw new Error(`detail.complexId mismatch: expected ${expected.complexId}, got ${detail.complexId}`);
  }
  toRequiredNumber(detail.parcelId, 'detail.parcelId');
  toNullableNumber(detail.complexId, 'detail.complexId');
  toNullableNumber(detail.latitude, 'detail.latitude');
  toNullableNumber(detail.longitude, 'detail.longitude');
  toNullableString(detail.address, 'detail.address');
  toNullableString(detail.tradeName, 'detail.tradeName');
  toRequiredString(detail.name, 'detail.name');
}

function assertTradeEnvelope(envelope, expected) {
  assertRecord(envelope, 'trade envelope');
  if (expected.parcelId != null && toRequiredNumber(envelope.parcelId, 'trades.parcelId') !== expected.parcelId) {
    throw new Error(`trades.parcelId mismatch: expected ${expected.parcelId}, got ${envelope.parcelId}`);
  }
  if (expected.complexId != null && toRequiredNumber(envelope.complexId, 'trades.complexId') !== expected.complexId) {
    throw new Error(`trades.complexId mismatch: expected ${expected.complexId}, got ${envelope.complexId}`);
  }
  assertArray(envelope.trades, 'trades.trades');
  assertNonEmpty(envelope.trades, 'trades.trades');
  for (const trade of envelope.trades.slice(0, 5)) {
    assertRecord(trade, 'trade item');
    toRequiredNumber(trade.tradeId, 'trade.tradeId');
    toRequiredString(trade.dealDate, 'trade.dealDate');
    toRequiredNumber(trade.exclArea, 'trade.exclArea');
    toRequiredNumber(trade.dealAmount, 'trade.dealAmount');
    toNullableString(trade.aptDong, 'trade.aptDong');
    toNullableNumber(trade.floor, 'trade.floor');
  }
}

function assertParcelComplexSummary(summary) {
  assertRecord(summary, 'parcel complex summary');
  toRequiredNumber(summary.complexId, 'parcel complex complexId');
  toRequiredString(summary.complexName, 'parcel complex complexName');
  toRequiredNumber(summary.parcelId, 'parcel complex parcelId');
  toNullableNumber(summary.latitude, 'parcel complex latitude');
  toNullableNumber(summary.longitude, 'parcel complex longitude');
  toNullableString(summary.address, 'parcel complex address');
}

function assertRegionSummary(region) {
  assertRecord(region, 'region summary');
  toRequiredNumber(region.id, 'region.id');
  toRequiredString(region.name, 'region.name');
}

function assertRegionDetail(region, expectedId) {
  assertRegionSummary(region);
  if (toRequiredNumber(region.id, 'region.id') !== expectedId) {
    throw new Error(`region.id mismatch: expected ${expectedId}, got ${region.id}`);
  }
  toRequiredNumber(region.latitude, 'region.latitude');
  toRequiredNumber(region.longitude, 'region.longitude');
  assertArray(region.children, 'region.children');
  for (const child of region.children) {
    assertRegionSummary(child);
  }
}

function assertRequiredHits(paths) {
  const missing = paths.filter((path) => !hits.some((hit) => matchesHitPath(hit.path, path)));
  if (missing.length > 0) {
    throw new Error(`실데이터 smoke에서 누락된 API hit: ${missing.join(', ')}`);
  }
}

function matchesHitPath(actualPath, expectedPath) {
  return actualPath === expectedPath || actualPath.startsWith(`${expectedPath}?`);
}

function assertArray(value, name) {
  if (!Array.isArray(value)) {
    throw new Error(`${name} response must be an array`);
  }
}

function assertNonEmpty(value, name) {
  if (value.length === 0) {
    throw new Error(`${name} response must include real backend data`);
  }
}

function assertRecord(value, name) {
  if (!isRecord(value)) {
    throw new Error(`${name} response must be an object`);
  }
}

function isRecord(value) {
  return typeof value === 'object' && value !== null;
}

function toRequiredNumber(value, name) {
  if (typeof value !== 'number' && (typeof value !== 'string' || value.trim().length === 0)) {
    throw new Error(`${name} must be a number`);
  }
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    throw new Error(`${name} must be a finite number`);
  }
  return parsed;
}

function toNullableNumber(value, name) {
  if (value == null) {
    return null;
  }
  return toRequiredNumber(value, name);
}

function toRequiredString(value, name) {
  if (typeof value !== 'string' || value.length === 0) {
    throw new Error(`${name} must be a non-empty string`);
  }
  return value;
}

function toNullableString(value, name) {
  if (value == null) {
    return null;
  }
  if (typeof value !== 'string') {
    throw new Error(`${name} must be a string or null`);
  }
  return value.length === 0 ? null : value;
}

function query(params) {
  return new URLSearchParams(
    Object.entries(params).map(([key, value]) => [key, String(value)]),
  ).toString();
}

function normalizeBaseUrl(value) {
  return value.endsWith('/') ? value.slice(0, -1) : value;
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : error);
  process.exitCode = 1;
});
