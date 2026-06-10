const DEFAULT_API_BASE_URL = 'http://localhost:8080';
const API_BASE_URL = normalizeBaseUrl(
  process.env.VITE_API_SERVER_IP
  ?? process.env.HOME_SEARCH_API_BASE_URL
  ?? DEFAULT_API_BASE_URL,
);

const SAMPLE_BOUNDS = {
  swLat: 37.45,
  swLng: 126.85,
  neLat: 37.7,
  neLng: 127.2,
};

const failures = [];

async function main() {
  console.log(`live-api-smoke: 실제 API 기준 검증 시작 (${API_BASE_URL})`);

  const complexMarkers = await postJson('/api/v1/map/complexes', {
    ...SAMPLE_BOUNDS,
    pyeongMin: null,
    pyeongMax: null,
    priceEokMin: null,
    priceEokMax: null,
    ageMin: null,
    ageMax: null,
    unitMin: null,
    unitMax: null,
  });
  expectArray('POST /api/v1/map/complexes', complexMarkers);
  const complexMarker = expectFirst(
    'POST /api/v1/map/complexes',
    complexMarkers,
    (item) => item?.parcelId != null && item?.lat != null && item?.lng != null,
  );
  expectNumber('complex marker parcelId', complexMarker.parcelId);
  expectNumber('complex marker lat', complexMarker.lat);
  expectNumber('complex marker lng', complexMarker.lng);
  expectNumber('complex marker unitCntSum', complexMarker.unitCntSum);

  const regionMarkers = await postJson('/api/v1/map/regions', {
    ...SAMPLE_BOUNDS,
    region: 'si-gun-gu',
  });
  expectArray('POST /api/v1/map/regions', regionMarkers);
  expectFirst('POST /api/v1/map/regions', regionMarkers, (item) =>
    item?.id != null && typeof item?.name === 'string',
  );

  const searchResults = await getJson('/api/v1/search/complexes?q=Sample');
  expectArray('GET /api/v1/search/complexes?q=Sample', searchResults);
  const searchResult = expectFirst('GET /api/v1/search/complexes?q=Sample', searchResults, (item) =>
    item?.complexId != null && item?.parcelId != null && typeof item?.complexName === 'string',
  );

  const suggestions = await getJson('/api/v1/search/complexes/suggestions?q=Sample');
  expectArray('GET /api/v1/search/complexes/suggestions?q=Sample', suggestions);
  expectFirst('GET /api/v1/search/complexes/suggestions?q=Sample', suggestions, (item) =>
    item?.complexId === searchResult.complexId && item?.parcelId === searchResult.parcelId,
  );

  const rootRegions = await getJson('/api/v1/region');
  expectArray('GET /api/v1/region', rootRegions);
  const rootRegion = expectFirst('GET /api/v1/region', rootRegions, (item) =>
    item?.id != null && typeof item?.name === 'string',
  );

  const regionDetail = await getJson(`/api/v1/region/${rootRegion.id}`);
  expectObject(`GET /api/v1/region/${rootRegion.id}`, regionDetail);
  expectArray('region detail children', regionDetail.children);

  const regionComplexes = await getJson(`/api/v1/region/${rootRegion.id}/complexes?limit=20&offset=0`);
  expectArray(`GET /api/v1/region/${rootRegion.id}/complexes`, regionComplexes);
  expectFirst(`GET /api/v1/region/${rootRegion.id}/complexes`, regionComplexes, (item) =>
    item?.complexId === searchResult.complexId && item?.parcelId === searchResult.parcelId,
  );

  const parcelId = Number(searchResult.parcelId);
  const complexId = Number(searchResult.complexId);

  const parcelDetail = await getJson(`/api/v1/detail/${parcelId}?complexId=${complexId}`);
  assertComplexDetail(`GET /api/v1/detail/${parcelId}?complexId=${complexId}`, parcelDetail, {
    parcelId,
    complexId,
  });

  const parcelComplexes = await getJson(`/api/v1/detail/${parcelId}/complexes`);
  expectArray(`GET /api/v1/detail/${parcelId}/complexes`, parcelComplexes);
  expectFirst(`GET /api/v1/detail/${parcelId}/complexes`, parcelComplexes, (item) =>
    item?.complexId === complexId && item?.parcelId === parcelId,
  );

  const directComplexDetail = await getJson(`/api/v1/complex/${complexId}`);
  assertComplexDetail(`GET /api/v1/complex/${complexId}`, directComplexDetail, {
    parcelId,
    complexId,
  });

  const parcelTrades = await getJson(`/api/v1/trade/${parcelId}?complexId=${complexId}`);
  assertTradePayload(`GET /api/v1/trade/${parcelId}?complexId=${complexId}`, parcelTrades, {
    parcelId,
    complexId,
  });

  const directComplexTrades = await getJson(`/api/v1/complex/${complexId}/trades`);
  assertTradePayload(`GET /api/v1/complex/${complexId}/trades`, directComplexTrades, {
    parcelId,
    complexId,
  });

  if (failures.length > 0) {
    throw new Error(`live API contract failed:\n- ${failures.join('\n- ')}`);
  }

  console.log('live-api-smoke: pass');
}

async function getJson(path) {
  return fetchJson(path, { method: 'GET' });
}

async function postJson(path, body) {
  return fetchJson(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
}

async function fetchJson(path, init) {
  let response;
  try {
    response = await fetch(new URL(path, API_BASE_URL), init);
  } catch (error) {
    throw new Error(
      `${init.method} ${path} failed to reach ${API_BASE_URL}: ${
        error instanceof Error ? error.message : String(error)
      }`,
    );
  }
  if (!response.ok) {
    const body = await response.text();
    throw new Error(`${init.method} ${path} failed: ${response.status} ${body}`);
  }
  return response.json();
}

function assertComplexDetail(label, detail, expected) {
  expectObject(label, detail);
  expectNumber(`${label} parcelId`, detail.parcelId);
  expectNumber(`${label} complexId`, detail.complexId);
  expectString(`${label} name`, detail.name);
  expectString(`${label} address`, detail.address);
  if (Number(detail.parcelId) !== expected.parcelId) {
    failures.push(`${label}: parcelId expected ${expected.parcelId}, got ${detail.parcelId}`);
  }
  if (Number(detail.complexId) !== expected.complexId) {
    failures.push(`${label}: complexId expected ${expected.complexId}, got ${detail.complexId}`);
  }
}

function assertTradePayload(label, payload, expected) {
  expectObject(label, payload);
  expectNumber(`${label} parcelId`, payload.parcelId);
  expectNumber(`${label} complexId`, payload.complexId);
  expectArray(`${label} trades`, payload.trades);
  expectFirst(`${label} trades`, payload.trades, (item) =>
    item?.tradeId != null
    && typeof item?.dealDate === 'string'
    && item?.exclArea != null
    && item?.dealAmount != null,
  );
  if (Number(payload.parcelId) !== expected.parcelId) {
    failures.push(`${label}: parcelId expected ${expected.parcelId}, got ${payload.parcelId}`);
  }
  if (Number(payload.complexId) !== expected.complexId) {
    failures.push(`${label}: complexId expected ${expected.complexId}, got ${payload.complexId}`);
  }
}

function expectFirst(label, values, predicate) {
  if (!Array.isArray(values)) {
    failures.push(`${label}: expected array before selecting a matching item`);
    return {};
  }
  const item = values.find(predicate);
  if (item == null) {
    failures.push(`${label}: expected at least one matching item`);
    return values[0] ?? {};
  }
  return item;
}

function expectObject(label, value) {
  if (typeof value !== 'object' || value == null || Array.isArray(value)) {
    failures.push(`${label}: expected object`);
  }
}

function expectArray(label, value) {
  if (!Array.isArray(value)) {
    failures.push(`${label}: expected array`);
  }
}

function expectNumber(label, value) {
  if (typeof value !== 'number' && (typeof value !== 'string' || value.trim().length === 0)) {
    failures.push(`${label}: expected number`);
    return;
  }
  if (!Number.isFinite(Number(value))) {
    failures.push(`${label}: expected finite number`);
  }
}

function expectString(label, value) {
  if (typeof value !== 'string' || value.length === 0) {
    failures.push(`${label}: expected non-empty string`);
  }
}

function normalizeBaseUrl(value) {
  const trimmed = value.trim();
  if (/^[a-z][a-z\d+\-.]*:\/\//i.test(trimmed)) {
    return trimmed.endsWith('/') ? trimmed : `${trimmed}/`;
  }
  return `http://${trimmed}${trimmed.endsWith('/') ? '' : '/'}`;
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : error);
  process.exitCode = 1;
});
