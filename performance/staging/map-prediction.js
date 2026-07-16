import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const baseUrl = required('BASE_URL').replace(/\/$/, '');
const mapBounds = JSON.parse(required('MAP_BOUNDS_JSON'));
const readyComplexId = required('READY_COMPLEX_ID');
const missComplexId = required('MISS_COMPLEX_ID');

const mapColdDuration = new Trend('map_cold_duration', true);
const mapWarmDuration = new Trend('map_warm_duration', true);
const mapErrors = new Rate('map_error_rate');
const mapResponses = new Counter('map_response_count');
const mapRows = new Trend('map_marker_rows');
const predictionReadyDuration = new Trend('prediction_ready_duration', true);
const predictionMissDuration = new Trend('prediction_miss_duration', true);
const predictionErrors = new Rate('prediction_error_rate');

let firstMapRequest = true;

export const options = {
  scenarios: {
    staging_evidence: {
      executor: 'per-vu-iterations',
      vus: 3,
      iterations: 5,
      maxDuration: '2m',
    },
  },
  thresholds: {
    map_cold_duration: ['p(95)<2500'],
    map_warm_duration: ['p(95)<100'],
    map_error_rate: ['rate<0.01'],
    prediction_ready_duration: ['p(95)<1000'],
    prediction_miss_duration: ['p(95)<3000'],
    prediction_error_rate: ['rate<0.01'],
  },
};

export default function () {
  const mapResponse = http.post(`${baseUrl}/api/v1/map/complexes`, JSON.stringify(mapBounds), {
    headers: { 'Content-Type': 'application/json', 'X-Performance-Probe': 'staging-evidence' },
    tags: { operation: firstMapRequest ? 'map-cold-first-request' : 'map-warm-repeat' },
  });
  const mapPayload = jsonOrNull(mapResponse);
  const mapOk = check(mapResponse, {
    'map status is 200': (response) => response.status === 200,
    'map response is array': () => Array.isArray(mapPayload),
  });
  (firstMapRequest ? mapColdDuration : mapWarmDuration).add(mapResponse.timings.duration);
  firstMapRequest = false;
  mapErrors.add(!mapOk);
  mapResponses.add(1);
  if (mapOk) mapRows.add(mapPayload.length);

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

function jsonOrNull(response) {
  try {
    return response.json();
  } catch (_) {
    return null;
  }
}
