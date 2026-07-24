import { Link } from 'react-router-dom';

import { INSIGHT_METRICS, REGION_MODE, type InsightMetric } from './insightMetricConfig';

export function MapModeNavigation({
  activeMetric,
  insightSearch,
  isRegionActive,
}: {
  activeMetric: InsightMetric | null;
  insightSearch: string;
  isRegionActive: boolean;
}) {
  const scopeParams = new URLSearchParams(insightSearch);
  const scope = scopeParams.get('scope') === 'NATIONWIDE' ? 'NATIONWIDE' : 'SIDO';
  const regionCode = scopeParams.get('regionCode');

  return (
    <nav aria-label="지도 탐색 모드" className="map-mode-navigation">
      <Link aria-current={isRegionActive ? 'page' : undefined} aria-label="지역 탐색" to="/">
        <REGION_MODE.icon aria-hidden="true" />
        <span>{REGION_MODE.label}</span>
      </Link>
      {INSIGHT_METRICS.map((config) => {
        const params = new URLSearchParams();
        params.set('metric', config.metric);
        params.set('scope', scope);
        if (scope === 'SIDO' && regionCode) {
          params.set('regionCode', regionCode);
        }
        return (
          <Link
            aria-current={activeMetric === config.metric ? 'page' : undefined}
            aria-label={`${config.label} 인사이트`}
            key={config.metric}
            to={`/insights?${params.toString()}`}
          >
            <config.icon aria-hidden="true" />
            <span>{config.label}</span>
          </Link>
        );
      })}
    </nav>
  );
}
