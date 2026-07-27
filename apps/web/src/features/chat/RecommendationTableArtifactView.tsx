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
  if (artifact.version === 2) return <AgentRecommendationTable artifact={artifact} />;
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

function AgentRecommendationTable({
  artifact,
}: {
  artifact: Extract<RecommendationTableArtifact, { version: 2 }>;
}) {
  return (
    <section className="chatbot-recommendation-table chatbot-agent-recommendations">
      <h4>{artifact.title}</h4>
      <p className="chatbot-comparison-basis">
        적용 기준 · {artifact.basis.scopeLabel} · 균형 비교(BALANCED_V1)
      </p>
      <ol className="chatbot-agent-recommendation-list">
        {artifact.rows.map((row) => (
          <li key={row.complexId}>
            <div className="chatbot-agent-recommendation-heading">
              <strong>{row.order}. {row.complexName}</strong>
              <span>{roleLabel(row.role)}</span>
            </div>
            <p>{row.summary}</p>
            <div className="chatbot-agent-recommendation-reasons">
              <div>
                <strong>강점</strong>
                <ul>{row.strengths.map((item) => <li key={item.factIds.join(':')}>{item.text}</li>)}</ul>
              </div>
              <div>
                <strong>tradeoff</strong>
                <ul>{row.tradeoffs.map((item) => <li key={item.factIds.join(':')}>{item.text}</li>)}</ul>
              </div>
            </div>
          </li>
        ))}
      </ol>
    </section>
  );
}

function roleLabel(role: Extract<RecommendationTableArtifact, { version: 2 }>['rows'][number]['role']) {
  return {
    BALANCED: '균형', TRADE_ACTIVITY: '거래 활동', SCALE: '규모', NEWER: '연식',
    TRANSIT: '교통', EDUCATION: '교육', LIFESTYLE: '생활 인프라',
  }[role];
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
