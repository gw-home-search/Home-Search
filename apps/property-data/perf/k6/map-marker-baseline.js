import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Trend } from 'k6/metrics';

const BASE_URL = trimTrailingSlash(__ENV.BASE_URL || 'http://localhost:8080');
const SCENARIO = __ENV.SCENARIO || 'baseline';
const TARGET_RPS = positiveNumberFromEnv('TARGET_RPS', 1);
const COMPLEX_WEIGHT = nonNegativeNumberFromEnv('COMPLEX_WEIGHT', 4);
const REGION_WEIGHT = nonNegativeNumberFromEnv('REGION_WEIGHT', 1);
const P95_THRESHOLD_MS = positiveNumberFromEnv('P95_THRESHOLD_MS', 0);
const REQUEST_TIMEOUT = __ENV.REQUEST_TIMEOUT || '60s';
const GRACEFUL_STOP = __ENV.GRACEFUL_STOP || '1m';
const COMPLEX_CASE = __ENV.COMPLEX_CASE || '';
const REGION_CASE = __ENV.REGION_CASE || '';

const COMPLEX_MARKER_DURATION = new Trend('complex_marker_duration', true);
const REGION_MARKER_DURATION = new Trend('region_marker_duration', true);
const COMPLEX_MARKER_COUNT = new Trend('complex_marker_count');
const REGION_MARKER_COUNT = new Trend('region_marker_count');

const defaultThresholds = {
  http_req_failed: ['rate<0.01'],
  checks: ['rate>0.99'],
};

if (P95_THRESHOLD_MS > 0) {
  defaultThresholds['http_req_duration{endpoint:complexes}'] = [`p(95)<${P95_THRESHOLD_MS}`];
  defaultThresholds['http_req_duration{endpoint:regions}'] = [`p(95)<${P95_THRESHOLD_MS}`];
}

const sharedOptions = {
  thresholds: defaultThresholds,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const scenarioOptions = {
  smoke: {
    ...sharedOptions,
    scenarios: {
      marker_smoke: {
        executor: 'shared-iterations',
        vus: 1,
        iterations: positiveNumberFromEnv('SMOKE_ITERATIONS', 6),
        maxDuration: __ENV.SMOKE_MAX_DURATION || '2m',
        gracefulStop: GRACEFUL_STOP,
      },
    },
  },
  baseline: {
    ...sharedOptions,
    scenarios: {
      marker_baseline: {
        executor: 'ramping-arrival-rate',
        timeUnit: '1s',
        preAllocatedVUs: Math.max(10, TARGET_RPS),
        maxVUs: Math.max(50, TARGET_RPS * 3),
        gracefulStop: GRACEFUL_STOP,
        stages: [
          { duration: __ENV.RAMP_UP || '30s', target: TARGET_RPS },
          { duration: __ENV.STEADY || '2m', target: TARGET_RPS },
          { duration: __ENV.RAMP_DOWN || '30s', target: 0 },
        ],
      },
    },
  },
  stress: {
    ...sharedOptions,
    scenarios: {
      marker_stress: {
        executor: 'ramping-arrival-rate',
        timeUnit: '1s',
        preAllocatedVUs: Math.max(20, TARGET_RPS * 2),
        maxVUs: Math.max(100, TARGET_RPS * 6),
        gracefulStop: GRACEFUL_STOP,
        stages: [
          { duration: __ENV.RAMP_UP || '1m', target: TARGET_RPS },
          { duration: __ENV.STEADY || '1m', target: TARGET_RPS * 2 },
          { duration: __ENV.STRESS_STEADY || '2m', target: TARGET_RPS * 3 },
          { duration: __ENV.RAMP_DOWN || '1m', target: 0 },
        ],
      },
    },
  },
};

export const options = scenarioOptions[SCENARIO] || scenarioOptions.baseline;

const headers = {
  'Content-Type': 'application/json',
};

const complexMarkerRequests = [
  {
    name: 'seed-wide',
    body: {
      swLat: 37.45,
      swLng: 126.85,
      neLat: 37.70,
      neLng: 127.20,
      pyeongMin: null,
      pyeongMax: null,
      priceEokMin: null,
      priceEokMax: null,
      ageMin: null,
      ageMax: null,
      unitMin: null,
      unitMax: null,
    },
  },
  {
    name: 'seed-narrow',
    body: {
      swLat: 37.50,
      swLng: 127.03,
      neLat: 37.53,
      neLng: 127.06,
      pyeongMin: null,
      pyeongMax: null,
      priceEokMin: null,
      priceEokMax: null,
      ageMin: null,
      ageMax: null,
      unitMin: null,
      unitMax: null,
    },
  },
  {
    name: 'price-filter',
    body: {
      swLat: 37.45,
      swLng: 126.85,
      neLat: 37.70,
      neLng: 127.20,
      pyeongMin: null,
      pyeongMax: null,
      priceEokMin: 10,
      priceEokMax: 20,
      ageMin: null,
      ageMax: null,
      unitMin: null,
      unitMax: null,
    },
  },
  {
    name: 'unit-filter',
    body: {
      swLat: 37.45,
      swLng: 126.85,
      neLat: 37.70,
      neLng: 127.20,
      pyeongMin: null,
      pyeongMax: null,
      priceEokMin: null,
      priceEokMax: null,
      ageMin: null,
      ageMax: null,
      unitMin: 100,
      unitMax: 2000,
    },
  },
];

const regionMarkerRequests = [
  {
    name: 'si-do',
    body: {
      swLat: 33.0,
      swLng: 124.0,
      neLat: 39.0,
      neLng: 132.0,
      region: 'si-do',
    },
  },
  {
    name: 'si-gun-gu',
    body: {
      swLat: 37.45,
      swLng: 126.85,
      neLat: 37.70,
      neLng: 127.20,
      region: 'si-gun-gu',
    },
  },
  {
    name: 'eup-myeon-dong',
    body: {
      swLat: 37.50,
      swLng: 127.03,
      neLat: 37.53,
      neLng: 127.06,
      region: 'eup-myeon-dong',
    },
  },
];

export default function () {
  if (SCENARIO === 'smoke') {
    if (chooseSmokeEndpoint()) {
      requestComplexMarkers(smokeRequestCase(complexMarkerRequests));
      return;
    }
    requestRegionMarkers(smokeRequestCase(regionMarkerRequests));
    return;
  }

  if (chooseEndpoint()) {
    requestComplexMarkers(selectedRequestCase(complexMarkerRequests, COMPLEX_CASE));
    return;
  }
  requestRegionMarkers(selectedRequestCase(regionMarkerRequests, REGION_CASE));
}

function requestComplexMarkers(requestCase) {
  const response = http.post(
    `${BASE_URL}/api/v1/map/complexes`,
    JSON.stringify(requestCase.body),
    {
      headers,
      timeout: REQUEST_TIMEOUT,
      tags: {
        endpoint: 'complexes',
        case: requestCase.name,
      },
    }
  );

  COMPLEX_MARKER_DURATION.add(response.timings.duration);
  const parsed = parseJsonArray(response);
  COMPLEX_MARKER_COUNT.add(parsed.items.length);

  check(response, {
    'complex marker status is 200': (r) => r.status === 200,
    'complex marker response is an array': () => parsed.isArray,
    'complex marker response keeps canonical shape': () => hasValidComplexMarkerShape(parsed.items),
    'complex marker response hides audit fields': () => hidesAuditFields(parsed.items),
  });
}

function requestRegionMarkers(requestCase) {
  const response = http.post(
    `${BASE_URL}/api/v1/map/regions`,
    JSON.stringify(requestCase.body),
    {
      headers,
      timeout: REQUEST_TIMEOUT,
      tags: {
        endpoint: 'regions',
        case: requestCase.name,
      },
    }
  );

  REGION_MARKER_DURATION.add(response.timings.duration);
  const parsed = parseJsonArray(response);
  REGION_MARKER_COUNT.add(parsed.items.length);

  check(response, {
    'region marker status is 200': (r) => r.status === 200,
    'region marker response is an array': () => parsed.isArray,
    'region marker response keeps canonical shape': () => hasValidRegionMarkerShape(parsed.items),
    'region marker response hides audit fields': () => hidesAuditFields(parsed.items),
  });
}

function parseJsonArray(response) {
  try {
    const parsed = response.json();
    if (Array.isArray(parsed)) {
      return {
        isArray: true,
        items: parsed,
      };
    }
  } catch (error) {
    return {
      isArray: false,
      items: [],
    };
  }
  return {
    isArray: false,
    items: [],
  };
}

function hasValidComplexMarkerShape(items) {
  return items.every((marker) => (
    isNumber(marker.parcelId)
    && isOptionalNumber(marker.complexId)
    && isOptionalString(marker.name)
    && isNumber(marker.lat)
    && isNumber(marker.lng)
    && isOptionalNumber(marker.latestDealAmount)
    && isNumber(marker.unitCntSum)
  ));
}

function hasValidRegionMarkerShape(items) {
  return items.every((marker) => (
    isNumber(marker.id)
    && typeof marker.name === 'string'
    && isNumber(marker.lat)
    && isNumber(marker.lng)
    && isOptionalNumber(marker.trend)
  ));
}

function hidesAuditFields(items) {
  const forbiddenFields = [
    'complexPk',
    'complex_pk',
    'aptSeq',
    'apt_seq',
    'source',
    'sourceKey',
    'source_key',
  ];
  return items.every((item) => forbiddenFields.every((field) => !(field in item)));
}

function chooseEndpoint() {
  const totalWeight = COMPLEX_WEIGHT + REGION_WEIGHT;
  if (totalWeight <= 0) {
    return true;
  }

  return Math.random() * totalWeight < COMPLEX_WEIGHT;
}

function chooseSmokeEndpoint() {
  if (COMPLEX_WEIGHT <= 0) {
    return false;
  }
  if (REGION_WEIGHT <= 0) {
    return true;
  }
  return exec.scenario.iterationInTest % 2 === 0;
}

function smokeRequestCase(items) {
  return items[Math.floor(exec.scenario.iterationInTest / 2) % items.length];
}

function randomItem(items) {
  return items[Math.floor(Math.random() * items.length)];
}

function selectedRequestCase(items, caseName) {
  if (!caseName) {
    return randomItem(items);
  }
  return items.find((item) => item.name === caseName) || randomItem(items);
}

function isNumber(value) {
  return typeof value === 'number' && Number.isFinite(value);
}

function isOptionalNumber(value) {
  return value === null || value === undefined || isNumber(value);
}

function isOptionalString(value) {
  return value === null || value === undefined || typeof value === 'string';
}

function positiveNumberFromEnv(name, fallback) {
  const value = Number(__ENV[name]);
  return Number.isFinite(value) && value > 0 ? value : fallback;
}

function nonNegativeNumberFromEnv(name, fallback) {
  const value = Number(__ENV[name]);
  return Number.isFinite(value) && value >= 0 ? value : fallback;
}

function trimTrailingSlash(value) {
  return value.replace(/\/+$/, '');
}

export function handleSummary(data) {
  const output = {
    stdout: textSummary(data),
  };
  if (__ENV.SUMMARY_EXPORT) {
    output[__ENV.SUMMARY_EXPORT] = JSON.stringify(data, null, 2);
  }
  return output;
}

function textSummary(data) {
  const metrics = data.metrics;
  const lines = [
    '',
    'Home Search map marker k6 summary',
    `scenario=${SCENARIO}`,
    `base_url=${BASE_URL}`,
    `complex_case=${COMPLEX_CASE || 'random'}`,
    `region_case=${REGION_CASE || 'random'}`,
    `request_timeout=${REQUEST_TIMEOUT}`,
    `graceful_stop=${GRACEFUL_STOP}`,
    metricLine(metrics, 'http_reqs', 'count'),
    metricLine(metrics, 'http_req_failed', 'rate'),
    metricLine(metrics, 'checks', 'rate'),
    metricLine(metrics, 'dropped_iterations', 'count'),
    metricLine(metrics, 'http_req_duration', 'p(95)'),
    metricLine(metrics, 'complex_marker_duration', 'p(95)'),
    metricLine(metrics, 'region_marker_duration', 'p(95)'),
    metricLine(metrics, 'complex_marker_count', 'avg'),
    metricLine(metrics, 'region_marker_count', 'avg'),
    '',
  ];
  return `${lines.filter(Boolean).join('\n')}\n`;
}

function metricLine(metrics, metricName, valueName) {
  const metric = metrics[metricName];
  if (!metric || !metric.values || metric.values[valueName] === undefined) {
    return `${metricName}.${valueName}=n/a`;
  }
  return `${metricName}.${valueName}=${metric.values[valueName]}`;
}
