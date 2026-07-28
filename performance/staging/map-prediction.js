import http from 'k6/http';
import exec from 'k6/execution';
import crypto from 'k6/crypto';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const baseUrl = required('BASE_URL').replace(/\/$/, '');
const mapBounds = JSON.parse(required('MAP_BOUNDS_JSON'));
const readyComplexId = required('READY_COMPLEX_ID');
const missComplexId = required('MISS_COMPLEX_ID');
const expectedPeakRps = positiveNumber(required('EXPECTED_PEAK_RPS'), 'EXPECTED_PEAK_RPS');
const expectedMapMarkerCount = positiveNumber(required('MAP_MARKER_EXPECTED_COUNT'), 'MAP_MARKER_EXPECTED_COUNT');
const expectedMapMarkerHash = required('MAP_MARKER_CANONICAL_SHA256');

const mapColdDuration = new Trend('map_cold_duration', true);
const mapWarmDuration = new Trend('map_warm_duration', true);
const mapErrors = new Rate('map_error_rate');
const mapResponses = new Counter('map_response_count');
const mapRows = new Trend('map_marker_rows');
const predictionReadyDuration = new Trend('prediction_ready_duration', true);
const predictionMissDuration = new Trend('prediction_miss_duration', true);
const predictionErrors = new Rate('prediction_error_rate');

export const options = {
  scenarios: {
    map_cold_gate: {
      executor: 'shared-iterations',
      exec: 'coldMap',
      vus: 3,
      iterations: 3,
      maxDuration: '30s',
    },
    expected_peak_x2: {
      executor: 'constant-arrival-rate',
      exec: 'peakTraffic',
      startTime: '10s',
      rate: expectedPeakRps * 2,
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: Math.max(10, expectedPeakRps * 2),
      maxVUs: Math.max(30, expectedPeakRps * 6),
    },
  },
  thresholds: {
    map_cold_duration: ['count>=3', 'p(95)<2000'],
    map_warm_duration: ['count>=3', 'p(95)<2000'],
    map_error_rate: ['rate<0.01'],
    prediction_ready_duration: ['p(95)<1000'],
    prediction_miss_duration: ['p(95)<3000'],
    prediction_error_rate: ['rate<0.01'],
  },
};

export function coldMap() {
  const uniqueBounds = {
    ...mapBounds,
    priceEokMin: exec.vu.idInTest * 0.0000001,
  };
  runMapProbe(uniqueBounds, true);
}

export function peakTraffic() {
  runMapProbe(mapBounds, false);

  const readyResponse = http.get(`${baseUrl}/api/v1/complex/${readyComplexId}`, {
    tags: { operation: 'prediction-ready-cache-hit' },
  });
  const readyPayload = jsonOrNull(readyResponse);
  const readyOk = check(readyResponse, {
    'READY detail status is 200': (response) => response.status === 200,
    'prediction cache entry is READY': () => readyPayload?.prediction?.status === 'READY',
  });
  predictionReadyDuration.add(readyResponse.timings.duration);
  predictionErrors.add(!readyOk);

  if (exec.scenario.iterationInTest === 0) {
    const missResponse = http.get(`${baseUrl}/api/v1/complex/${missComplexId}`, {
      tags: { operation: 'prediction-cache-miss' },
    });
    const missPayload = jsonOrNull(missResponse);
    const missOk = check(missResponse, {
      'cache miss detail status is 200': (response) => response.status === 200,
      'cache miss exposes prediction state': () => typeof missPayload?.prediction?.status === 'string',
    });
    predictionMissDuration.add(missResponse.timings.duration);
    predictionErrors.add(!missOk);
  }
}

export default peakTraffic;

function runMapProbe(bounds, cold) {
  const mapResponse = http.post(`${baseUrl}/api/v1/map/complexes`, JSON.stringify(bounds), {
    headers: { 'Content-Type': 'application/json', 'X-Performance-Probe': 'staging-gate' },
    tags: { operation: cold ? 'map-cold-unique-key' : 'map-warm-peak-x2' },
  });
  const mapPayload = jsonOrNull(mapResponse);
  const mapOk = check(mapResponse, {
    'map status is 200': (response) => response.status === 200,
    'map response is array': () => Array.isArray(mapPayload),
    'map marker count matches generation evidence': () => mapPayload?.length === expectedMapMarkerCount,
    'map marker canonical hash matches generation evidence': () =>
      Array.isArray(mapPayload) && canonicalMarkerHash(mapPayload) === expectedMapMarkerHash,
  });
  (cold ? mapColdDuration : mapWarmDuration).add(mapResponse.timings.duration);
  mapErrors.add(!mapOk);
  mapResponses.add(1);
  if (mapOk) mapRows.add(mapPayload.length);
}

function canonicalMarkerHash(markers) {
  const canonicalRows = markers
    .map((marker) =>
      [
        marker.parcelId,
        marker.complexId ?? '',
        marker.name ?? '',
        marker.lat,
        marker.lng,
        marker.latestDealAmount ?? '',
        marker.unitCntSum ?? '',
      ].join('|'),
    )
    .sort();
  return crypto.sha256(canonicalRows.join('\n'), 'hex');
}

export function handleSummary(data) {
  return {
    stdout: `staging performance evidence: ${JSON.stringify(data.metrics)}\n`,
    'performance-evidence/k6-summary.json': JSON.stringify(data, null, 2),
  };
}

function required(name) {
  const value = __ENV[name];
  if (!value) throw new Error(`${name} is required`);
  return value;
}

function positiveNumber(value, name) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed <= 0 || !Number.isInteger(parsed)) {
    throw new Error(`${name} must be a positive integer`);
  }
  return parsed;
}

function jsonOrNull(response) {
  try {
    return response.json();
  } catch (_) {
    return null;
  }
}
