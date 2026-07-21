import type {
  RecommendationMetricKey,
  RecommendationTableArtifact,
  RecommendationTableMetric,
} from './artifactContract';

export function RecommendationTableArtifactView({
  artifact,
}: {
  artifact: RecommendationTableArtifact;
}) {
  return (
    <section className="chatbot-recommendation-table">
      <h4>{artifact.title}</h4>
      <div className="chatbot-comparison-scroll" tabIndex={0}>
        <table>
          <thead>
            <tr>
              <th scope="col">후보</th>
              <th scope="col">세대수</th>
              {artifact.basis.criteriaOrder.map((key) => (
                <th key={key} scope="col">{metricLabel(key)}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {artifact.rows.map((row) => (
              <tr key={row.complexId}>
                <th scope="row">{row.order}. {row.complexName}</th>
                <td>{row.unitCount == null ? '확인 불가' : `${row.unitCount.toLocaleString('ko-KR')}세대`}</td>
                {artifact.basis.criteriaOrder.map((key) => (
                  <td key={key}>{formatMetric(key, row.metrics[key])}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="chatbot-comparison-basis">
        {artifact.basis.scopeLabel}
        {artifact.basis.minimumUnitCount == null
          ? ''
          : ` · 최소 ${artifact.basis.minimumUnitCount.toLocaleString('ko-KR')}세대`}
        {artifact.basis.scopeType === 'STATION_RADIUS' ? ' · 직선거리 기준' : ''}
      </p>
    </section>
  );
}

function metricLabel(key: RecommendationMetricKey): string {
  return {
    ACADEMY: '학원 접근성',
    SCHOOL: '학교 위치',
    TRANSIT: '철도역 거리',
    SHOPPING: '대규모점포 거리',
  }[key];
}

function formatMetric(
  key: RecommendationMetricKey,
  metric: RecommendationTableMetric | undefined,
): string {
  if (metric == null) return '확인 불가';
  if (metric.availability === 'unavailable') return `확인 불가 · ${metric.reason}`;
  if (key === 'ACADEMY') {
    return `${metric.value?.toLocaleString('ko-KR')}곳${
      metric.nearestDistanceMeters == null
        ? ''
        : ` · 최근접 ${metric.nearestDistanceMeters.toLocaleString('ko-KR')}m`
    }`;
  }
  return `${metric.value?.toLocaleString('ko-KR')}m`;
}
